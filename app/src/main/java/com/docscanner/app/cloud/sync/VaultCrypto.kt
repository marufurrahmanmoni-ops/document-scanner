package com.docscanner.app.cloud.sync

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for cloud backup (backend.md "Encryption model").
 *
 * - A random 256-bit **backup key** encrypts everything that leaves the phone.
 * - The backup key is stored in the cloud only **wrapped** by a key derived from the user's backup passphrase
 *   (PBKDF2-HMAC-SHA256, random salt). The server never sees the passphrase or the backup key.
 * - Subkeys are derived from the backup key with HMAC-SHA256 (HKDF-expand style): one for AES-256-GCM,
 *   one for naming storage objects, so object names do not reveal file hashes.
 * - Every ciphertext binds associated data (user id + record or blob id), so blobs cannot be swapped.
 *
 * Pure JVM crypto (javax.crypto), unit-tested in VaultCryptoTest.
 */
object VaultCrypto {

    const val VAULT_VERSION = 1
    const val DEFAULT_ITERATIONS = 310_000
    private const val FORMAT_V1: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** What is stored in Firestore at users/{uid}/vault/key. Contains no secret in usable form. */
    data class WrappedVault(
        val version: Int,
        val salt: ByteArray,
        val iterations: Int,
        val wrappedKey: ByteArray,
    )

    class WrongPassphraseException : Exception("wrong backup passphrase")
    class CorruptDataException(cause: Throwable? = null) : Exception("encrypted data is damaged or not ours", cause)

    /** The unlocked backup key and its derived subkeys. Keep in memory only; persist wrapped by the Keystore. */
    class BackupKey(val raw: ByteArray) {
        init { require(raw.size == 32) }
        internal val encKey = SecretKeySpec(expand(raw, "docscanner/enc/v1"), "AES")
        internal val nameKey = expand(raw, "docscanner/name/v1")
    }

    fun newVault(passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): Pair<BackupKey, WrappedVault> {
        val key = ByteArray(32).also { random.nextBytes(it) }
        return BackupKey(key) to wrap(key, passphrase, iterations)
    }

    fun wrap(rawKey: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): WrappedVault {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val kek = kek(passphrase, salt, iterations)
        val wrapped = seal(SecretKeySpec(kek, "AES"), rawKey, VAULT_AD)
        return WrappedVault(VAULT_VERSION, salt, iterations, wrapped)
    }

    fun unlock(passphrase: CharArray, vault: WrappedVault): BackupKey {
        require(vault.version == VAULT_VERSION) { "unknown vault version ${vault.version}" }
        val kek = kek(passphrase, vault.salt, vault.iterations)
        return try {
            BackupKey(open(SecretKeySpec(kek, "AES"), vault.wrappedKey, VAULT_AD))
        } catch (e: CorruptDataException) {
            throw WrongPassphraseException()
        }
    }

    /** Re-wraps the same backup key with a new passphrase. Existing cloud data stays readable. */
    fun changePassphrase(key: BackupKey, newPassphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): WrappedVault =
        wrap(key.raw, newPassphrase, iterations)

    fun encrypt(key: BackupKey, plaintext: ByteArray, associatedData: String): ByteArray =
        seal(key.encKey, plaintext, associatedData.toByteArray())

    fun decrypt(key: BackupKey, ciphertext: ByteArray, associatedData: String): ByteArray =
        open(key.encKey, ciphertext, associatedData.toByteArray())

    /** Storage object name for a blob: HMAC of its SHA-256, so the server cannot test for known files. */
    fun objectName(key: BackupKey, sha256: String): String =
        hmac(key.nameKey, "blob:$sha256".toByteArray()).toHex()

    // ------------------------------------------------------------------ internals

    private val VAULT_AD = "docscanner/vault/v1".toByteArray()

    private fun kek(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun seal(key: SecretKeySpec, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(ad)
        val body = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + IV_LEN + body.size).put(FORMAT_V1).put(iv).put(body).array()
    }

    private fun open(key: SecretKeySpec, data: ByteArray, ad: ByteArray): ByteArray {
        if (data.size < 1 + IV_LEN + TAG_BITS / 8 || data[0] != FORMAT_V1) throw CorruptDataException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 1, IV_LEN))
        cipher.updateAAD(ad)
        return try {
            cipher.doFinal(data, 1 + IV_LEN, data.size - 1 - IV_LEN)
        } catch (e: AEADBadTagException) {
            throw CorruptDataException(e)
        }
    }

    private fun expand(key: ByteArray, label: String): ByteArray = hmac(key, label.toByteArray() + byteArrayOf(1))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
