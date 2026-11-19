package com.docscanner.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import com.docscanner.app.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Decoding, rotation, perspective warp and encoding. Every Mat returned is owned by the caller. */
object ImageOps {

    data class Bounds(val width: Int, val height: Int)

    fun bounds(jpeg: ByteArray): Bounds {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return Bounds(o.outWidth, o.outHeight)
    }

    /**
     * Decodes with the largest power-of-two sample size that keeps the long edge at or above [minLongEdge].
     * Never decodes a 50 MP original at full size when the output is smaller.
     */
    fun decodeSampled(jpeg: ByteArray, minLongEdge: Int): Bitmap {
        val b = bounds(jpeg)
        var sample = 1
        val longEdge = max(b.width, b.height)
        while (longEdge / (sample * 2) >= minLongEdge) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            ?: error("could not decode image")
    }

    /** RGBA bitmap to an RGB Mat. */
    fun toRgbMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    fun toGrayMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    /** 1- or 3-channel Mat to an ARGB_8888 bitmap. */
    fun toBitmap(mat: Mat): Bitmap {
        val bmp = createBitmap(mat.cols(), mat.rows())
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    /** Rotates in place-ish: returns a new Mat for 90/180/270, a clone for 0. */
    fun rotate(src: Mat, degrees: Int): Mat {
        val out = Mat()
        when (((degrees % 360) + 360) % 360) {
            90 -> Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, out, Core.ROTATE_180)
            270 -> Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(out)
        }
        return out
    }

    fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (((degrees % 360) + 360) % 360 == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Perspective-corrects [src] to the [quad]. The output size comes from the quad's edge lengths,
     * clamped so the long edge is at most [maxLongEdge].
     */
    fun warp(src: Mat, quad: Quad, maxLongEdge: Int): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val p = quad.points.map { Point(it.x * w, it.y * h) }
        val top = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val bottom = hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val left = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val right = hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        var outW = max(top, bottom)
        var outH = max(left, right)
        val scale = (maxLongEdge / max(outW, outH)).coerceAtMost(1.0)
        outW = (outW * scale).coerceAtLeast(16.0)
        outH = (outH * scale).coerceAtLeast(16.0)
        val srcPts = MatOfPoint2f(p[0], p[1], p[2], p[3])
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1, 0.0), Point(outW - 1, outH - 1), Point(0.0, outH - 1),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        val interp = if (scale < 0.5) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        Imgproc.warpPerspective(
            src, out, m, Size(outW.roundToInt().toDouble(), outH.roundToInt().toDouble()), interp, Core.BORDER_REPLICATE,
        )
        srcPts.release()
        dstPts.release()
        m.release()
        return out
    }

    /** Downscales so the long edge is at most [longEdge]. Returns a new Mat. */
    fun fit(src: Mat, longEdge: Int): Mat {
        val out = Mat()
        val scale = longEdge.toDouble() / max(src.cols(), src.rows())
        if (scale >= 1.0) {
            src.copyTo(out)
        } else {
            Imgproc.resize(src, out, Size(src.cols() * scale, src.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        }
        return out
    }

    fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    fun encodeJpeg(mat: Mat, quality: Int): ByteArray {
        val bmp = toBitmap(mat)
        try {
            return encodeJpeg(bmp, quality)
        } finally {
            bmp.recycle()
        }
    }

}
