package com.docscanner.app

import android.app.ActivityManager
import android.content.Context
import com.docscanner.app.cloud.AuthRepository
import com.docscanner.app.cloud.CloudServices
import com.docscanner.app.cloud.sync.SyncEngine
import com.docscanner.app.cloud.sync.SyncScheduler
import com.docscanner.app.cloud.sync.SyncSettings
import com.docscanner.app.cloud.sync.VaultRepository
import kotlinx.coroutines.launch
import com.docscanner.app.data.DocumentRepository
import com.docscanner.app.data.ExportRepository
import com.docscanner.app.data.ImageLoader
import com.docscanner.app.data.PageRenderer
import com.docscanner.app.data.SettingsRepository
import com.docscanner.app.data.db.AppDatabase
import com.docscanner.app.security.KeyManager
import com.docscanner.app.storage.BlobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Hilt is planned (architecture.md section 2) and replaces this
 * when the app splits into modules. Every dependency is created once, lazily.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val keys by lazy { KeyManager(app) }

    val blobs by lazy { BlobStore(app.filesDir, keys.streamingAead) }

    val database by lazy { AppDatabase.create(app, keys.databasePassphrase()) }

    private val renderer by lazy { PageRenderer(blobs) }

    private val lowRam: Boolean by lazy {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice || am.memoryClass < 256
    }

    val documents by lazy { DocumentRepository(database, blobs, renderer, appScope, lowRam) }

    val exports by lazy { ExportRepository(app, documents) }

    val settings by lazy { SettingsRepository(app) }

    val images by lazy { ImageLoader(blobs) }

    /** null when this build has no Firebase configuration: every cloud feature then explains itself as off. */
    val cloud: CloudServices? by lazy { CloudServices.create(app) }

    val auth by lazy { AuthRepository(cloud) }

    val syncSettings by lazy { SyncSettings(app) }

    val vault by lazy { VaultRepository(cloud, keys) }

    val syncScheduler by lazy { SyncScheduler(app) }

    /** null without cloud config. */
    val syncEngine: SyncEngine? by lazy {
        cloud?.let { SyncEngine(it, database, blobs, vault, syncSettings, documents) }
    }

    /** Debounced background sync after local edits, only while the user has backup on. */
    fun wireSync() {
        if (cloud == null) {
            appScope.launch { database.outbox().clearAll() } // nothing will ever drain it in this build
            return
        }
        documents.onLocalChange = {
            appScope.launch {
                val s = syncSettings.current()
                if (s.enabled) syncScheduler.requestSoon(s.wifiOnly)
            }
        }
        appScope.launch {
            val s = syncSettings.current()
            if (s.enabled && auth.currentUid() != null) syncScheduler.startPeriodic(s.wifiOnly) else syncScheduler.stopAll()
            // With backup off the outbox only grows; turning backup on queues everything anyway.
            if (!s.enabled) database.outbox().clearAll()
        }
    }

    /** Signs out of Firebase and Google, stops sync and forgets the backup key here. Local documents stay. */
    suspend fun signOut(context: Context) {
        syncScheduler.stopAll()
        syncEngine?.forgetLocalSyncState()
        auth.signOut(context)
    }
}
