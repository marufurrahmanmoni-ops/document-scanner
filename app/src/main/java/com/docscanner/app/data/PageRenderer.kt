package com.docscanner.app.data

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.docscanner.app.imaging.FilterEngine
import com.docscanner.app.imaging.ImageOps
import com.docscanner.app.model.Filter
import com.docscanner.app.model.Page
import com.docscanner.app.model.Quad
import com.docscanner.app.storage.BlobStore
import org.opencv.core.Mat

/**
 * Turns an immutable original + recipe into pixels (architecture.md section 4).
 * original -> sampled decode -> source rotation -> perspective warp -> page rotation -> filter -> adjustments.
 */
class PageRenderer(private val blobs: BlobStore) {

    data class Rendered(val jpeg: ByteArray, val width: Int, val height: Int, val thumbJpeg: ByteArray)

    /** Full render for storage or export. */
    fun render(page: Page, longEdge: Int, jpegQuality: Int, brightness: Int = 0, contrast: Int = 0): Rendered {
        val result = renderMat(page.originalSha, page.sourceRotation, page.quad, page.rotation, page.filter, longEdge,
            brightness, contrast)
        try {
            val jpeg = ImageOps.encodeJpeg(result, jpegQuality)
            val thumb = ImageOps.fit(result, THUMB_EDGE)
            val thumbJpeg = try { ImageOps.encodeJpeg(thumb, 80) } finally { thumb.release() }
            return Rendered(jpeg, result.cols(), result.rows(), thumbJpeg)
        } finally {
            result.release()
        }
    }

    /** Fast preview for the enhance screen: same recipe, small proxy. */
    fun preview(page: Page, filter: Filter, rotation: Int, longEdge: Int = PREVIEW_EDGE): Bitmap {
        val m = renderMat(page.originalSha, page.sourceRotation, page.quad, rotation, filter, longEdge, 0, 0)
        try {
            return ImageOps.toBitmap(m)
        } finally {
            m.release()
        }
    }

    /** The upright original, downscaled, for the crop screen. */
    fun uprightOriginal(page: Page, longEdge: Int = CROP_EDGE): Bitmap {
        val bytes = blobs.read(page.originalSha)
        val decoded = ImageOps.decodeSampled(bytes, longEdge)
        val rotated = ImageOps.rotateBitmap(decoded, page.sourceRotation)
        if (rotated !== decoded) decoded.recycle()
        val scale = longEdge.toFloat() / maxOf(rotated.width, rotated.height)
        if (scale >= 1f) return rotated
        val scaled = rotated.scale((rotated.width * scale).toInt(), (rotated.height * scale).toInt())
        if (scaled !== rotated) rotated.recycle()
        return scaled
    }

    private fun renderMat(
        originalSha: String,
        sourceRotation: Int,
        quad: Quad?,
        rotation: Int,
        filter: Filter,
        longEdge: Int,
        brightness: Int,
        contrast: Int,
    ): Mat {
        val bytes = blobs.read(originalSha)
        // Decode a little above the target so the warp has detail to sample from.
        val bmp = ImageOps.decodeSampled(bytes, (longEdge * 1.15).toInt())
        val rgb = ImageOps.toRgbMat(bmp)
        bmp.recycle()
        val upright = ImageOps.rotate(rgb, sourceRotation)
        rgb.release()
        val warped = ImageOps.warp(upright, quad ?: Quad.FULL, longEdge)
        upright.release()
        val turned = ImageOps.rotate(warped, rotation)
        warped.release()
        val filtered = FilterEngine.apply(turned, filter)
        turned.release()
        if (brightness == 0 && contrast == 0) return filtered
        val adjusted = FilterEngine.adjust(filtered, brightness, contrast)
        filtered.release()
        return adjusted
    }

    companion object {
        const val THUMB_EDGE = 512
        const val PREVIEW_EDGE = 1400
        const val CROP_EDGE = 1600
    }
}
