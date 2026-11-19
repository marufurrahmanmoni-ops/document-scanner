package com.docscanner.app.feature.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.docscanner.app.imaging.EdgeDetector
import com.docscanner.app.imaging.ImageOps
import com.docscanner.app.model.Quad
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Runs edge detection on every analysis frame (640x480 YUV, keep-only-latest).
 * Uses the Y plane directly as the gray image, rotates it upright, and reports a normalised quad.
 */
class DocumentAnalyzer(
    private val onResult: (quad: Quad?, sharpness: Double) -> Unit,
) : ImageAnalysis.Analyzer {

    private var rowBuffer = ByteArray(0)
    private var previous: Quad? = null

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val gray = Mat(h, w, CvType.CV_8UC1)
            if (rowBuffer.size < w) rowBuffer = ByteArray(maxOf(w, rowStride))
            if (pixelStride == 1 && rowStride == w && buffer.remaining() >= w * h) {
                val all = ByteArray(w * h)
                buffer.rewind()
                buffer.get(all, 0, w * h)
                gray.put(0, 0, all)
            } else {
                val row = ByteArray(w)
                for (r in 0 until h) {
                    val base = r * rowStride
                    if (pixelStride == 1) {
                        buffer.position(base)
                        buffer.get(row, 0, w)
                    } else {
                        for (c in 0 until w) row[c] = buffer.get(base + c * pixelStride)
                    }
                    gray.put(r, 0, row)
                }
            }
            val upright = ImageOps.rotate(gray, image.imageInfo.rotationDegrees)
            gray.release()
            val result = try { EdgeDetector.detect(upright, previous) } finally { upright.release() }
            previous = result.quad
            onResult(result.quad, result.sharpness)
        } catch (t: Throwable) {
            onResult(null, 0.0)
        } finally {
            image.close()
        }
    }
}
