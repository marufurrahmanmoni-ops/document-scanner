package com.docscanner.app.imaging

import com.docscanner.app.model.NPoint
import com.docscanner.app.model.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Classical document edge detection (architecture.md 4.1).
 * Input: an 8-bit single-channel image, upright. Output: the page quad in normalised coordinates, or null.
 * Two edge maps are tried (Canny, and a morphological gradient + Otsu that catches white paper on a light desk),
 * and the best-scoring convex quad wins.
 */
object EdgeDetector {

    private const val WORK_LONG_EDGE = 480.0
    private const val MIN_AREA_FRACTION = 0.15

    data class Result(val quad: Quad?, val sharpness: Double)

    fun detect(gray: Mat, previous: Quad? = null): Result {
        val scale = WORK_LONG_EDGE / max(gray.cols(), gray.rows())
        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            gray.copyTo(small)
        }
        try {
            val sharpness = laplacianVariance(small)
            val blurred = Mat()
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)

            val candidates = ArrayList<Quad>()
            cannyEdgeMaps(blurred).forEach { candidates += quadsFrom(it, small.cols(), small.rows()); it.release() }
            gradientEdges(blurred).also { candidates += quadsFrom(it, small.cols(), small.rows()); it.release() }
            blurred.release()

            val best = candidates.maxByOrNull { score(it, previous) }
            return Result(best, sharpness)
        } finally {
            small.release()
        }
    }

    /**
     * Canny edge maps with thresholds taken from the gradient magnitude (90th percentile), not from brightness,
     * plus one fixed low pair. Brightness-based thresholds missed white paper on a light desk entirely in testing
     * (0/12); gradient-based thresholds found 12/12 (build-log.md, imaging validation).
     */
    private fun cannyEdgeMaps(src: Mat): List<Mat> {
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(src, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(src, gy, CvType.CV_32F, 0, 1, 3)
        val mag = Mat()
        Core.magnitude(gx, gy, mag)
        gx.release()
        gy.release()
        val hi = percentile(mag, 0.90).coerceAtLeast(30.0)
        mag.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val maps = listOf(0.4 * hi to hi, 20.0 to 60.0).map { (lo, up) ->
            val edges = Mat()
            Imgproc.Canny(src, edges, lo, up)
            Imgproc.dilate(edges, edges, kernel)
            edges
        }
        kernel.release()
        return maps
    }

    /** Percentile of a CV_32F image via a 512-bin histogram over 0..1024. */
    private fun percentile(m: Mat, p: Double): Double {
        val data = FloatArray((m.total()).toInt())
        m.get(0, 0, data)
        val bins = IntArray(512)
        for (v in data) bins[(v / 2f).toInt().coerceIn(0, 511)]++
        val target = (data.size * p).toInt()
        var acc = 0
        for (i in bins.indices) {
            acc += bins[i]
            if (acc >= target) return i * 2.0
        }
        return 1022.0
    }

    private fun gradientEdges(src: Mat): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val grad = Mat()
        Imgproc.morphologyEx(src, grad, Imgproc.MORPH_GRADIENT, kernel)
        val bin = Mat()
        Imgproc.threshold(grad, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
        grad.release()
        kernel.release()
        return bin
    }

    private fun quadsFrom(edges: Mat, w: Int, h: Int): List<Quad> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val frameArea = w.toDouble() * h
        val out = ArrayList<Quad>()
        for (c in contours) {
            try {
                if (Imgproc.contourArea(c) < frameArea * MIN_AREA_FRACTION) continue
                val c2f = MatOfPoint2f(*c.toArray())
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
                val pts = approx.toArray()
                c2f.release()
                approx.release()
                if (pts.size != 4) continue
                val asInt = MatOfPoint(*pts)
                val convex = Imgproc.isContourConvex(asInt)
                asInt.release()
                if (!convex) continue
                val quad = Quad.fromUnordered(pts.map { NPoint((it.x / w).toFloat(), (it.y / h).toFloat()) })
                if (anglesSane(quad) && sidesOnEdges(quad, edges, w, h)) out += quad
            } finally {
                c.release()
            }
        }
        return out
    }

    /**
     * A real page has four straight sides lying on edge pixels. A ragged contour made of unrelated lines (cables,
     * wood grain, clutter) can still simplify to four points, but its "sides" cut across empty space. Each side must
     * have edge pixels (within 2 px) under at least 70% of its middle 80%. Bowed book pages stay within 2% of the
     * chord, which the dilated edge map and the 2 px window absorb.
     */
    private fun sidesOnEdges(q: Quad, edges: Mat, w: Int, h: Int): Boolean {
        val p = q.points
        val buf = ByteArray(1)
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val ax = a.x * w; val ay = a.y * h; val bx = b.x * w; val by = b.y * h
            val samples = 40
            var hit = 0
            for (k in 0 until samples) {
                val t = 0.1f + 0.8f * k / (samples - 1)
                val x = (ax + (bx - ax) * t).toInt()
                val y = (ay + (by - ay) * t).toInt()
                var found = false
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    edges.get(yy, xx, buf)
                    if (buf[0].toInt() != 0) { found = true; break@loop }
                }
                if (found) hit++
            }
            if (hit < samples * 0.7) return false
        }
        return true
    }

    /** Every corner between 45 and 135 degrees. Rejects slivers and kites. */
    private fun anglesSane(q: Quad): Boolean {
        val p = q.points
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val v1x = a.x - b.x
            val v1y = a.y - b.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val n = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (n < 1e-6f) return false
            val deg = Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / n).coerceIn(-1f, 1f).toDouble()))
            if (deg < 45 || deg > 135) return false
        }
        return true
    }

    private fun score(q: Quad, previous: Quad?): Double {
        var s = q.area().toDouble()
        if (previous != null) s -= 0.5 * q.maxDistanceTo(previous)
        // Prefer rectangles: penalise unequal opposite sides.
        val top = dist(q.tl, q.tr)
        val bottom = dist(q.bl, q.br)
        val left = dist(q.tl, q.bl)
        val right = dist(q.tr, q.br)
        s -= 0.1 * (abs(top - bottom) / max(top, bottom) + abs(left - right) / max(left, right))
        return s
    }

    private fun dist(a: NPoint, b: NPoint): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** Variance of the Laplacian: a cheap focus measure. */
    fun laplacianVariance(gray: Mat): Double {
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val sd = std.toArray().firstOrNull() ?: 0.0
        lap.release()
        mean.release()
        std.release()
        return sd * sd
    }

}
