package com.docscanner.app.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.docscanner.app.BuildConfig
import com.docscanner.app.storage.BlobStore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/** Server-verified subscription state, read from users/{uid}/entitlements/current (written only by Cloud Functions). */
data class Entitlement(
    val active: Boolean,
    val productId: String?,
    val state: String,
    val expiresAtMillis: Long?,
    val autoRenewing: Boolean,
)

/** What the subscription screen shows for the plan. */
data class PlanOffer(val title: String, val price: String, val period: String, val details: ProductDetails, val offerToken: String)

sealed interface BillingEvent {
    data object Verified : BillingEvent
    data object Pending : BillingEvent
    data object Cancelled : BillingEvent
    data class Failed(val code: Int) : BillingEvent
}

/**
 * Google Play Billing for subscriptions. The app never decides entitlement itself: every purchase token is sent to
 * the `verifyPurchase` Cloud Function, which checks it with Google Play, acknowledges it and writes the entitlement.
 */
class BillingRepository(
    private val context: Context,
    private val cloud: CloudServices?,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BillingEvent> = _events

    private val listener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { purchases.orEmpty().forEach { handle(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> _events.tryEmit(BillingEvent.Cancelled)
            else -> _events.tryEmit(BillingEvent.Failed(result.responseCode))
        }
    }

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }

    val available: Boolean get() = CloudConfig.subscriptions && cloud != null

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** The configured subscription and its base offer, or null if Play has nothing for this build/account. */
    suspend fun loadPlan(): PlanOffer? {
        if (!available || !connect()) return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(BuildConfig.PLAY_SUBSCRIPTION_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
            ),
        ).build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        val details = result.productDetailsList?.firstOrNull() ?: return null
        val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return null
        val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
        return PlanOffer(details.title, phase.formattedPrice, phase.billingPeriod, details, offer.offerToken)
    }

    /** Opens Google Play's purchase sheet. The purchase is tied to the hashed uid so it cannot unlock another account. */
    suspend fun purchase(activity: Activity, plan: PlanOffer, uid: String): Boolean {
        if (!connect()) return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(plan.details)
                        .setOfferToken(plan.offerToken)
                        .build(),
                ),
            )
            .setObfuscatedAccountId(BlobStore.sha256Hex(uid.toByteArray()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Re-checks purchases Play knows about (new phone, reinstall, or a verification that failed offline). */
    suspend fun restore(): Boolean {
        if (!available || !connect()) return false
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return false
        result.purchasesList.forEach { handle(it) }
        return true
    }

    private suspend fun handle(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> _events.emit(BillingEvent.Pending)
            Purchase.PurchaseState.PURCHASED -> {
                val ok = runCatching {
                    cloud!!.functions.getHttpsCallable("verifyPurchase").call(
                        mapOf("purchaseToken" to purchase.purchaseToken, "productId" to purchase.products.first()),
                    ).await()
                }.onFailure { Log.w("Billing", "verification failed", it) }.isSuccess
                _events.emit(if (ok) BillingEvent.Verified else BillingEvent.Failed(-1))
            }
            else -> Unit
        }
    }

    /** Live entitlement for [uid], from Firestore (works offline from the cache). */
    fun observeEntitlement(uid: String?): Flow<Entitlement?> {
        val cloud = cloud ?: return flowOf(null)
        if (uid == null) return flowOf(null)
        return callbackFlow {
            val reg: ListenerRegistration = cloud.firestore.document("users/$uid/entitlements/current")
                .addSnapshotListener { snap, _ ->
                    trySend(
                        snap?.takeIf { it.exists() }?.let {
                            Entitlement(
                                active = it.getBoolean("active") == true,
                                productId = it.getString("productId"),
                                state = it.getString("state").orEmpty(),
                                expiresAtMillis = it.getTimestamp("expiresAt")?.toDate()?.time,
                                autoRenewing = it.getBoolean("autoRenewing") == true,
                            )
                        },
                    )
                }
            awaitClose { reg.remove() }
        }
    }

    /** Google Play's own subscription page: cancelling is one tap there. */
    fun manageUrl(productId: String?): String =
        "https://play.google.com/store/account/subscriptions?package=${context.packageName}" +
            (productId?.let { "&sku=$it" } ?: "")
}
