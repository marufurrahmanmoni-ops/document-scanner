package com.docscanner.app.cloud

import android.content.Context
import android.util.Log
import com.docscanner.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage

/** Which cloud features this build can offer, from BuildConfig (see replica/backend.md for the variables). */
object CloudConfig {
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotEmpty() &&
            BuildConfig.FIREBASE_APP_ID.isNotEmpty() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty() &&
            BuildConfig.FIREBASE_STORAGE_BUCKET.isNotEmpty()

    val googleSignIn: Boolean get() = isConfigured && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()

    val subscriptions: Boolean get() = isConfigured && BuildConfig.PLAY_SUBSCRIPTION_ID.isNotEmpty()

    val functionsRegion: String get() = BuildConfig.FUNCTIONS_REGION.ifEmpty { "us-central1" }
}

/**
 * Firebase, initialised from BuildConfig instead of google-services.json, so the app builds and works offline
 * without a Firebase project. Created only when [CloudConfig.isConfigured].
 */
class CloudServices private constructor(val app: FirebaseApp) {

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance(app)

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(app).apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }

    val storage: FirebaseStorage get() = FirebaseStorage.getInstance(app)

    val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance(app, CloudConfig.functionsRegion)

    companion object {
        fun create(context: Context): CloudServices? {
            if (!CloudConfig.isConfigured) return null
            return runCatching {
                val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(
                        context,
                        FirebaseOptions.Builder()
                            .setApiKey(BuildConfig.FIREBASE_API_KEY)
                            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                            .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                            .build(),
                    )
                AppCheckInstaller.install(app)
                CloudServices(app)
            }.onFailure { Log.e("CloudServices", "Firebase init failed", it) }.getOrNull()
        }
    }
}
