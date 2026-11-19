package com.docscanner.app.data

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.docscanner.app.storage.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decrypts and decodes blobs for the UI, with a memory-only cache (decision D5: nothing decrypted touches disk).
 */
class ImageLoader(private val blobs: BlobStore) {

    private val cache = object : LruCache<String, ImageBitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(sha: String, maxEdge: Int): ImageBitmap? = cache.get("$sha@$maxEdge")

    suspend fun load(sha: String, maxEdge: Int): ImageBitmap? {
        val key = "$sha@$maxEdge"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = blobs.read(sha)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxEdge) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bmp?.asImageBitmap()?.also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    private companion object {
        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt()
    }
}
