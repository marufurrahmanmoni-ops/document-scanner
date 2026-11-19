package com.docscanner.app.cloud

import android.content.Context
import com.docscanner.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Deletes the account and everything the app stored for it in the cloud (backend.md "Account deletion").
 * Order matters: cloud data first (while still signed in, so Security Rules allow it), then the auth user.
 * If anything fails midway the `cleanupDeletedUser` Cloud Function removes what is left once the user is deleted.
 * Subscriptions are billed by Google Play and must be cancelled there; the UI says so before deleting.
 */
class AccountDeletion(private val c: AppContainer) {

    /** Call after re-authentication. [eraseLocal] also wipes every document on this phone. */
    suspend fun deleteEverything(context: Context, eraseLocal: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cloud = c.auth.requireCloud()
            val user = cloud.auth.currentUser ?: throw AuthException(AuthError.UNKNOWN)
            val uid = user.uid
            c.syncScheduler.stopAll()

            // 1. Encrypted files.
            val folder = cloud.storage.reference.child("users/$uid/blobs")
            var page = folder.list(500).await()
            while (true) {
                page.items.forEach { it.delete().await() }
                val token = page.pageToken ?: break
                page = folder.list(500, token).await()
            }

            // 2. Records, vault, profile (entitlements are server-owned and removed by the cleanup function).
            val userDoc = cloud.firestore.collection("users").document(uid)
            while (true) {
                val batch = userDoc.collection("records").limit(400).get().await()
                if (batch.isEmpty) break
                cloud.firestore.runBatch { b -> batch.documents.forEach { b.delete(it.reference) } }.await()
            }
            userDoc.collection("vault").document("key").delete().await()
            userDoc.delete().await()

            // 3. The account itself (requires a recent sign-in; the screen re-authenticates first).
            user.delete().await()

            // 4. This phone.
            c.syncEngine?.forgetLocalSyncState()
            c.auth.signOut(context)
            if (eraseLocal) c.documents.eraseEverything()
        }.recoverCatching { e ->
            throw if (e is AuthException) e else AuthException(
                if (e is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) AuthError.RECENT_LOGIN_REQUIRED
                else AuthError.UNKNOWN,
                e,
            )
        }
    }
}
