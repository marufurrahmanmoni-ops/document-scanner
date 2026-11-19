package com.docscanner.app

import android.app.ActivityManager
import android.content.Context
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
}
