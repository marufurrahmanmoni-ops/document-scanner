package com.docscanner.app.cloud.sync

import com.docscanner.app.cloud.CloudServices
import com.docscanner.app.security.KeyManager
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The user's backup key. Cloud copy: users/{uid}/vault/key holds only the passphrase-wrapped key.
 * Phone copy: the raw key wrapped by the Android Keystore (KeyManager), so backups run without asking again.
 */
class VaultRepository(private val cloud: CloudServices?, private val keys: KeyManager) {

    private val _unlocked = MutableStateFlow(keys.loadBackupKey() != null)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun localKey(): VaultCrypto.BackupKey? = keys.loadBackupKey()?.let { VaultCrypto.BackupKey(it) }

    /** true = a backup passphrase already exists for this account (restore on a new phone). Needs network. */
    suspend fun remoteVaultExists(uid: String): Boolean = withContext(Dispatchers.IO) {
        doc(uid).get(Source.SERVER).await().exists()
    }

    /** First-time setup: creates the key and stores only its wrapped form in the cloud. Fails if one exists. */
    suspend fun create(uid: String, passphrase: CharArray) = withContext(Dispatchers.Default) {
        require(passphrase.size >= MIN_PASSPHRASE) { "passphrase too short" }
        val (key, wrapped) = VaultCrypto.newVault(passphrase)
        val ref = doc(uid)
        cloud!!.firestore.runTransaction { tx ->
            if (tx.get(ref).exists()) throw IllegalStateException("vault already exists")
            tx.set(ref, wrapped.toMap())
        }.await()
        keys.saveBackupKey(key.raw)
        _unlocked.value = true
    }

    /** Unlocks the existing cloud vault on this phone. Throws WrongPassphraseException. */
    suspend fun unlock(uid: String, passphrase: CharArray) = withContext(Dispatchers.Default) {
        val snap = doc(uid).get(Source.SERVER).await()
        val wrapped = snap.toWrapped() ?: throw IllegalStateException("no vault")
        val key = VaultCrypto.unlock(passphrase, wrapped)
        keys.saveBackupKey(key.raw)
        _unlocked.value = true
    }

    /** Re-wraps the same key: all existing backups stay readable, the old passphrase stops working. */
    suspend fun changePassphrase(uid: String, newPassphrase: CharArray) = withContext(Dispatchers.Default) {
        require(newPassphrase.size >= MIN_PASSPHRASE) { "passphrase too short" }
        val key = localKey() ?: throw IllegalStateException("locked")
        doc(uid).set(VaultCrypto.changePassphrase(key, newPassphrase).toMap()).await()
    }

    fun forgetOnThisPhone() {
        keys.clearBackupKey()
        _unlocked.value = false
    }

    private fun doc(uid: String) = cloud!!.firestore.collection("users").document(uid).collection("vault").document("key")

    private fun VaultCrypto.WrappedVault.toMap() = hashMapOf(
        "version" to version,
        "salt" to Blob.fromBytes(salt),
        "iterations" to iterations,
        "wrappedKey" to Blob.fromBytes(wrappedKey),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toWrapped(): VaultCrypto.WrappedVault? {
        if (!exists()) return null
        return VaultCrypto.WrappedVault(
            version = (getLong("version") ?: return null).toInt(),
            salt = getBlob("salt")?.toBytes() ?: return null,
            iterations = (getLong("iterations") ?: return null).toInt(),
            wrappedKey = getBlob("wrappedKey")?.toBytes() ?: return null,
        )
    }

    companion object {
        const val MIN_PASSPHRASE = 10
    }
}
