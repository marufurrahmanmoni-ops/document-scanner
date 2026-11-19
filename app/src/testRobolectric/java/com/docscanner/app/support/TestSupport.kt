package com.docscanner.app.support

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.docscanner.app.AppContainer
import com.docscanner.app.data.db.AppDatabase
import com.docscanner.app.storage.BlobStore
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File

/** Robolectric application that does nothing at start (no OpenCV, no Keystore, no housekeeping). */
class TestApp : Application()

object TestSupport {
    /** A real AppContainer with an in-memory Room DB (plain SQLite) and a BlobStore under a throwaway Tink key. */
    fun container(context: Context, root: File = File(context.cacheDir, "test-blobs-${System.nanoTime()}")): AppContainer {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        return AppContainer(context, AppContainer.Overrides(database = db, blobs = BlobStore(root, testAead())))
    }

    fun testAead(): StreamingAead {
        StreamingAeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_4KB"))
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }
}
