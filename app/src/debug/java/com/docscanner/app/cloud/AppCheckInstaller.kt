package com.docscanner.app.cloud

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds use the App Check debug provider. On first run it logs a debug token (logcat tag
 * "DebugAppCheckProvider") that must be registered in Firebase console → App Check → Manage debug tokens.
 */
object AppCheckInstaller {
    fun install(app: FirebaseApp) {
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
