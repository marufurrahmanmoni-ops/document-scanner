package com.docscanner.app.feature.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.cloud.Account
import com.docscanner.app.cloud.BillingEvent
import com.docscanner.app.cloud.PlanOffer
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.ButtonVariant
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.components.SectionHeader
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.feature.common.shortDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class PlanLoad { LOADING, READY, UNAVAILABLE }

class SubscriptionViewModel(val c: AppContainer) : ViewModel() {
    val plan = MutableStateFlow<PlanOffer?>(null)
    val load = MutableStateFlow(PlanLoad.LOADING)
    val busy = MutableStateFlow(false)

    fun loadPlan() = viewModelScope.launch {
        load.value = PlanLoad.LOADING
        val p = runCatching { c.billing.loadPlan() }.getOrNull()
        plan.value = p
        load.value = if (p == null) PlanLoad.UNAVAILABLE else PlanLoad.READY
    }

    fun buy(activity: Activity, uid: String) = viewModelScope.launch {
        val p = plan.value ?: return@launch
        busy.value = true
        c.billing.purchase(activity, p, uid)
        busy.value = false
    }

    fun restore() = viewModelScope.launch {
        busy.value = true
        c.billing.restore()
        busy.value = false
    }
}

/** Subscription status and purchase. Entitlement comes from the server-written document, never from the client. */
@Composable
fun SubscriptionSection(account: Account, showMessage: suspend (Int) -> Unit) {
    val c = LocalAppContainer.current
    SectionHeader(stringResource(R.string.sub_title))
    if (!c.billing.available) {
        Text(stringResource(R.string.sub_unavailable_build), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val vm: SubscriptionViewModel = viewModel { SubscriptionViewModel(c) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entitlement by remember(account.uid) { c.billing.observeEntitlement(account.uid) }.collectAsStateWithLifecycle(null)
    val plan by vm.plan.collectAsStateWithLifecycle()
    val load by vm.load.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.loadPlan() }
    LaunchedEffect(vm) {
        c.billing.events.collect { e ->
            showMessage(
                when (e) {
                    BillingEvent.Verified -> R.string.sub_thanks
                    BillingEvent.Pending -> R.string.sub_pending
                    BillingEvent.Cancelled -> R.string.sub_cancelled
                    is BillingEvent.Failed -> R.string.sub_failed
                },
            )
        }
    }

    val ent = entitlement
    if (ent?.active == true) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
                Text(stringResource(R.string.sub_active), style = MaterialTheme.typography.titleSmall)
                ent.expiresAtMillis?.let {
                    Text(
                        stringResource(if (ent.autoRenewing) R.string.sub_renews else R.string.sub_ends, shortDate(it)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        ScannerButton(
            stringResource(R.string.sub_manage),
            { context.startActivity(Intent(Intent.ACTION_VIEW, c.billing.manageUrl(ent.productId).toUri())) },
            variant = ButtonVariant.SECONDARY,
        )
        return
    }

    when (load) {
        PlanLoad.LOADING -> Text(stringResource(R.string.backup_checking), style = MaterialTheme.typography.bodyMedium)
        PlanLoad.UNAVAILABLE -> Row {
            Text(stringResource(R.string.sub_unavailable_now), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ScannerButton(stringResource(R.string.try_again), { vm.loadPlan() }, variant = ButtonVariant.TEXT, size = ButtonSize.SM)
        }
        PlanLoad.READY -> plan?.let { p ->
            Text(p.title, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sub_price, p.price, periodLabel(p.period)), style = MaterialTheme.typography.bodyLarge)
            ScannerButton(
                stringResource(R.string.sub_subscribe),
                { context.findActivity()?.let { vm.buy(it, account.uid) } },
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.sub_cancel_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ScannerButton(stringResource(R.string.sub_restore), { scope.launch { vm.restore() } }, variant = ButtonVariant.TEXT, enabled = !busy)
}

@Composable
private fun periodLabel(iso: String): String = stringResource(
    when (iso) {
        "P1W" -> R.string.period_week
        "P1M" -> R.string.period_month
        "P3M" -> R.string.period_quarter
        "P6M" -> R.string.period_half_year
        "P1Y" -> R.string.period_year
        else -> R.string.period_other
    },
)

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
