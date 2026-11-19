import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Backend configuration (see replica/backend.md). Read from local.properties, then environment variables.
 * Nothing here is secret in the cryptographic sense (Firebase client config is public by design and protected by
 * Security Rules + App Check), but it is kept out of git so each developer/CI points at its own Firebase project.
 * Missing values build an app with cloud features switched off, never a broken app.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()
/**
 * Robolectric tests (Compose UI + Room on the JVM) live in src/testRobolectric and run with -Probolectric=true.
 * They are opt-in because Robolectric downloads ~100 MB Android runtime jars that some networks block.
 */
val robolectricTests = (findProperty("robolectric") as String?) == "true"

/**
 * Release signing comes from keystore.properties at the repo root (git-ignored), or from the environment in CI:
 *   storeFile=/absolute/path/upload-key.jks  storePassword=...  keyAlias=upload  keyPassword=...
 * Without it, release builds are produced unsigned and cannot be uploaded. Nothing secret is in source control.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)
val hasReleaseKey = signing("storeFile", "SIGNING_STORE_FILE") != null

fun quoted(name: String): String = "\"" + cfg(name).replace("\"", "") + "\""

android {
    namespace = "com.docscanner.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.marufurrahmanmoni_ops.docscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_API_KEY", quoted("FIREBASE_API_KEY"))
        buildConfigField("String", "FIREBASE_APP_ID", quoted("FIREBASE_APP_ID"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted("FIREBASE_PROJECT_ID"))
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", quoted("FIREBASE_STORAGE_BUCKET"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted("GOOGLE_WEB_CLIENT_ID"))
        buildConfigField("String", "FUNCTIONS_REGION", quoted("FUNCTIONS_REGION"))
        buildConfigField("String", "PLAY_SUBSCRIPTION_ID", quoted("PLAY_SUBSCRIPTION_ID"))
        ndk {
            // Real phones only. The App Bundle splits per ABI so users download one copy of OpenCV.
            // -Pabi=arm64-v8a builds a single-ABI APK for sideloading onto a phone (about a third of the size).
            val only = findProperty("abi") as String?
            abiFilters += if (only != null) listOf(only) else listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signing("storeFile", "SIGNING_STORE_FILE")!!)
                storePassword = signing("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signing("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Keep native libraries uncompressed and page-aligned (16 KB page-size requirement).
            useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("test") {
            if (robolectricTests) java.srcDir("src/testRobolectric/java")
        }
        // Exported Room schemas, so MigrationTestHelper can build v1 databases on a device.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs merged resources and the manifest to run Compose UI and Room on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.logging.enabled", "true")
        }
    }
}

kotlin {
    // Bytecode target 17; any installed JDK >= 17 can build (no fixed toolchain download).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // CameraX APIs return ListenableFuture; Firebase pulls the empty listenablefuture stub, so Guava provides it.
    implementation(libs.guava)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    implementation(libs.tink.android)
    implementation(libs.datastore.preferences)
    implementation(libs.opencv)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions) {
        // Legacy instance-ID (push messaging) is optional for callable functions; the app sends no push, so the
        // RECEIVE permission and device token it brings are left out.
        exclude(group = "com.google.firebase", module = "firebase-iid")
    }
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.billing.ktx)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    if (robolectricTests) {
        testImplementation(libs.robolectric)
        testImplementation(libs.androidx.test.core)
        testImplementation(libs.androidx.test.ext)
        testImplementation(platform(libs.androidx.compose.bom))
        testImplementation(libs.compose.ui.test.junit4)
        testImplementation(libs.room.testing)
        testImplementation(libs.work.testing)
    }
    // Desktop OpenCV natives so the app's real imaging code runs in JVM tests.
    testImplementation(libs.opencv.desktop)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
