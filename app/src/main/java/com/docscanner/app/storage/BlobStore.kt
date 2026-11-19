package com.docscanner.app.storage

import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Encrypted, content-addressed file store (architecture.md section 7).
 * A blob is named by the SHA-256 of its plaintext and encrypted with Tink StreamingAead,
 * with the hash as associated data so one blob cannot be swapped for another.
 * Writes are atomic: tmp file, fsync, rename. The DB row that references a blob is written after.
 */
class BlobStore(root: File, private val aead: StreamingAead) {

    private val blobsDir = File(root, "blobs").apply { mkdirs() }
    private val tmpDir = File(root, "blobs-tmp").apply { mkdirs() }

    fun put(bytes: ByteArray): String {
        val sha = sha256Hex(bytes)
        val target = fileFor(sha)
        if (target.exists()) return sha
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("blob", ".tmp", tmpDir)
        try {
            tmp.outputStream().use { fileOut ->
                aead.newEncryptingStream(fileOut, sha.toByteArray()).use { enc -> enc.write(bytes) }
                fileOut.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                if (!target.exists()) error("could not move blob into place")
            }
        } finally {
            tmp.delete()
        }
        return sha
    }

    fun open(sha: String): InputStream =
        aead.newDecryptingStream(fileFor(sha).inputStream(), sha.toByteArray())

    fun read(sha: String): ByteArray = open(sha).use { it.readBytes() }

    fun exists(sha: String): Boolean = fileFor(sha).exists()

    fun delete(sha: String) {
        fileFor(sha).delete()
    }

    /** Every stored hash, for garbage collection of unreferenced blobs. */
    fun allHashes(): List<Pair<String, Long>> =
        blobsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") }
            .map { it.name.removeSuffix(".bin") to it.lastModified() }.toList()

    fun clearTmp() {
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(sha: String): File {
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "bad blob id" }
        return File(File(blobsDir, sha.substring(0, 2)), "$sha.bin")
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
