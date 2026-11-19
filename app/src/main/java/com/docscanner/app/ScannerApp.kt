package com.docscanner.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class ScannerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV native library failed to load")
        }
        container = AppContainer(this)
        container.wireSync()
        container.appScope.launch {
            runCatching {
                container.exports.cleanup()
                container.documents.housekeeping()
            }.onFailure { Log.w(TAG, "housekeeping failed", it) }
        }
    }

    private companion object {
        const val TAG = "ScannerApp"
    }
}
