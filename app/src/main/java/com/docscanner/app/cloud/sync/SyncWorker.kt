package com.docscanner.app.cloud.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.docscanner.app.ScannerApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Background sync. Retries with exponential backoff on network or server errors; stops (no retry) when a
 * precondition is missing, e.g. the user turned backup off or signed out. The outbox keeps every change meanwhile.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ScannerApp).container
        val engine = container.syncEngine ?: return Result.success()
        return try {
            val report = engine.syncOnce()
            Log.i(TAG, "sync ok: pushed=${report.pushed} pulled=${report.pulled} conflicts=${report.conflicts}")
            Result.success()
        } catch (e: SyncPreconditionException) {
            Log.i(TAG, "sync skipped: ${e.reason}")
            container.syncSettings.recordError(e.reason.name)
            Result.success()
        } catch (e: VaultCrypto.WrongPassphraseException) {
            container.syncSettings.recordError("LOCKED")
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "sync failed (attempt $runAttemptCount)", e)
            container.syncSettings.recordError(errorCode(e))
            if (runAttemptCount < MAX_ATTEMPTS && transient(e)) Result.retry() else Result.failure()
        }
    }

    private fun transient(e: Exception): Boolean = when (e) {
        is FirebaseNetworkException, is IOException -> true
        is StorageException -> e.isRecoverableException || e.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED
        is FirebaseFirestoreException -> e.code in setOf(
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.ABORTED, FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
            FirebaseFirestoreException.Code.INTERNAL,
        )
        else -> false
    }

    private fun errorCode(e: Exception): String = when (e) {
        is FirebaseNetworkException, is IOException -> "NETWORK"
        is FirebaseFirestoreException -> "SERVER_" + e.code.name
        is StorageException -> "STORAGE_" + e.errorCode
        is VaultCrypto.CorruptDataException -> "CORRUPT"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_ATTEMPTS = 8
        const val UNIQUE_NOW = "sync-now"
        const val UNIQUE_PERIODIC = "sync-periodic"
    }
}

/** Schedules sync only while the user has backup turned on. */
class SyncScheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /** After a local change: waits a few seconds so a burst of edits becomes one sync. */
    fun requestSoon(wifiOnly: Boolean, delaySeconds: Long = 10) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(SyncWorker.UNIQUE_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** Periodic pull of changes made on other phones. */
    fun startPeriodic(wifiOnly: Boolean) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(SyncWorker.UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun stopAll() {
        wm.cancelUniqueWork(SyncWorker.UNIQUE_NOW)
        wm.cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC)
    }

    /** true while a sync is queued or running, for the "Syncing…" status. */
    fun observeRunning(): Flow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_NOW)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
}
