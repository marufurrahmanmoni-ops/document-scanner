package com.docscanner.app.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.security.SecureRandom

/**
 * Keys for data at rest (architecture.md section 7).
 * An Android Keystore AES-256-GCM master key wraps two Tink keysets:
 *  - blob keyset (StreamingAead) for page images
 *  - meta keyset (Aead) for the database passphrase
 * The keys are not bound to user authentication, so background work runs while the app is locked.
 */
class KeyManager(private val context: Context) {

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    val streamingAead: StreamingAead by lazy {
        keyset(BLOB_KEYSET, "AES256_GCM_HKDF_1MB").getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private val metaAead: Aead by lazy {
        keyset(META_KEYSET, "AES256_GCM").getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun keyset(name: String, template: String): KeysetHandle =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, name, PREFS)
            .withKeyTemplate(KeyTemplates.get(template))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle

    /** 32 random bytes, created once, stored encrypted with the meta keyset. */
    fun databasePassphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(DB_PASS_KEY, null)
        if (stored != null) {
            return metaAead.decrypt(Base64.decode(stored, Base64.NO_WRAP), DB_PASS_AD)
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val enc = metaAead.encrypt(pass, DB_PASS_AD)
        // commit = true: the passphrase must be on disk before the database file is created with it.
        prefs.edit(commit = true) { putString(DB_PASS_KEY, Base64.encodeToString(enc, Base64.NO_WRAP)) }
        return pass
    }

    private companion object {
        const val PREFS = "scanner_keys"
        const val BLOB_KEYSET = "blob_keyset"
        const val META_KEYSET = "meta_keyset"
        const val DB_PASS_KEY = "db_pass"
        const val MASTER_KEY_URI = "android-keystore://scanner_master"
        val DB_PASS_AD = "db-passphrase".toByteArray()
    }
}
