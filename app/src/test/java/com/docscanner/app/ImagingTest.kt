package com.docscanner.app

import com.docscanner.app.imaging.EdgeDetector
import com.docscanner.app.imaging.FilterEngine
import com.docscanner.app.model.Filter
import com.docscanner.app.model.NPoint
import com.docscanner.app.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random

/**
 * Runs the app's real EdgeDetector and FilterEngine (Kotlin, unchanged) on synthetic photos of the document
 * types in the test plan: A4 pages, receipts, ID cards, open books, low light, blur and clutter.
 * Uses desktop OpenCV natives (org.openpnp) so it runs on any JVM without a device.
 */
class ImagingTest {

    companion object {
        @BeforeClass @JvmStatic fun loadNative() = nu.pattern.OpenCV.loadLocally()
        private const val W = 1200
        private const val H = 900
    }

    private val rnd = Random(7)

    // ------------------------------------------------------------ scene builder

    private enum class Bg { DARK_WOOD, LIGHT_DESK, CLUTTER }

    /** Draws a page of [pw]x[ph] with text-like lines, warps it to [quad] (pixels) on a background. RGB out. */
    private fun scene(quadPx: List<Point>, pw: Int, ph: Int, bg: Bg, light: Double = 1.0, blurSigma: Double = 1.0,
                      noise: Double = 6.0, shadow: Boolean = true, gutter: Boolean = false): Mat {
        val page = Mat(ph, pw, CvType.CV_8UC3, Scalar(245.0, 244.0, 240.0))
        val lines = (ph - 80) / 28
        for (i in 0 until lines) {
            val y = 60.0 + i * 28
            val x2 = pw - 40.0 - rnd.nextInt(pw / 3)
            Imgproc.line(page, Point(30.0, y), Point(x2, y), Scalar(55.0, 55.0, 60.0), 5)
        }
        if (gutter) Imgproc.line(page, Point(pw / 2.0, 0.0), Point(pw / 2.0, ph.toDouble()), Scalar(170.0, 165.0, 160.0), 6)
        val canvas = when (bg) {
            Bg.DARK_WOOD -> Mat(H, W, CvType.CV_8UC3, Scalar(70.0, 50.0, 35.0))
            Bg.LIGHT_DESK -> Mat(H, W, CvType.CV_8UC3, Scalar(205.0, 200.0, 190.0))
            Bg.CLUTTER -> Mat(H, W, CvType.CV_8UC3, Scalar(70.0, 50.0, 35.0)).also { m ->
                repeat(40) {
                    val c = rnd.nextInt(255).toDouble()
                    Imgproc.line(m, Point(rnd.nextInt(W).toDouble(), rnd.nextInt(H).toDouble()),
                        Point(rnd.nextInt(W).toDouble(), rnd.nextInt(H).toDouble()), Scalar(c, c, c), 1 + rnd.nextInt(5))
                }
            }
        }
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(pw.toDouble(), 0.0), Point(pw.toDouble(), ph.toDouble()), Point(0.0, ph.toDouble()))
        val dst = MatOfPoint2f(*quadPx.toTypedArray())
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat(); Imgproc.warpPerspective(page, warped, m, Size(W.toDouble(), H.toDouble()))
        val mask = Mat(H, W, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(mask, listOf(MatOfPoint(*quadPx.toTypedArray())), Scalar(255.0))
        warped.copyTo(canvas, mask)
        if (shadow) {
            val grad = Mat(H, W, CvType.CV_32FC3)
            for (x in 0 until W) {
                val g = (0.6 + 0.45 * x / W).toFloat()
                Imgproc.line(grad, Point(x.toDouble(), 0.0), Point(x.toDouble(), H.toDouble()), Scalar(g.toDouble(), g.toDouble(), g.toDouble()))
            }
            val f = Mat(); canvas.convertTo(f, CvType.CV_32FC3); Core.multiply(f, grad, f); f.convertTo(canvas, CvType.CV_8UC3)
        }
        if (light != 1.0) canvas.convertTo(canvas, -1, light, 0.0)
        val n = Mat(H, W, CvType.CV_16SC3); Core.randn(n, 0.0, noise)
        val s = Mat(); canvas.convertTo(s, CvType.CV_16SC3); Core.add(s, n, s); s.convertTo(canvas, CvType.CV_8UC3)
        if (blurSigma > 0) Imgproc.GaussianBlur(canvas, canvas, Size(0.0, 0.0), blurSigma)
        return canvas
    }

    private fun gray(rgb: Mat) = Mat().also { Imgproc.cvtColor(rgb, it, Imgproc.COLOR_RGB2GRAY) }

    private fun iou(a: Quad, bPx: List<Point>): Double {
        val ma = Mat(H, W, CvType.CV_8UC1, Scalar(0.0)); val mb = Mat(H, W, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(ma, listOf(MatOfPoint(*a.points.map { Point(it.x * W.toDouble(), it.y * H.toDouble()) }.toTypedArray())), Scalar(1.0))
        Imgproc.fillPoly(mb, listOf(MatOfPoint(*bPx.toTypedArray())), Scalar(1.0))
        val inter = Mat(); Core.bitwise_and(ma, mb, inter); val uni = Mat(); Core.bitwise_or(ma, mb, uni)
        return Core.countNonZero(inter).toDouble() / Core.countNonZero(uni).coerceAtLeast(1)
    }

    private fun quad(cx: Double, cy: Double, hw: Double, hh: Double, jitter: Double = 40.0) = listOf(
        Point(cx - hw + j(jitter), cy - hh + j(jitter)), Point(cx + hw + j(jitter), cy - hh + j(jitter)),
        Point(cx + hw + j(jitter), cy + hh + j(jitter)), Point(cx - hw + j(jitter), cy + hh + j(jitter)),
    )
    private fun j(a: Double) = (rnd.nextDouble() * 2 - 1) * a

    /** Runs [n] scenes and returns how many were detected with IoU >= 0.9. */
    private fun passRate(n: Int, make: () -> Pair<Mat, List<Point>>): Int = (1..n).count {
        val (img, truth) = make()
        val q = EdgeDetector.detect(gray(img)).quad
        q != null && iou(q, truth) >= 0.9
    }

    // ------------------------------------------------------------ detection by document type

    @Test fun a4PageOnDarkDesk() {
        assertEquals(10, passRate(10) { val q = quad(600.0, 450.0, 300.0, 400.0); scene(q, 850, 1100, Bg.DARK_WOOD) to q })
    }

    @Test fun a4PageOnLightDesk_whiteOnWhite() {
        assertEquals(10, passRate(10) { val q = quad(600.0, 450.0, 300.0, 400.0); scene(q, 850, 1100, Bg.LIGHT_DESK) to q })
    }

    @Test fun pageOnClutteredBackground() {
        assertEquals(10, passRate(10) { val q = quad(600.0, 450.0, 300.0, 400.0); scene(q, 850, 1100, Bg.CLUTTER) to q })
    }

    @Test fun longNarrowReceipt() {
        val ok = passRate(10) { val q = quad(600.0, 450.0, 120.0, 420.0, jitter = 15.0); scene(q, 300, 1100, Bg.DARK_WOOD) to q }
        assertTrue("receipts: $ok/10", ok >= 9)
    }

    @Test fun identityCardSmallInFrame() {
        // ID-1 card 85.6 x 54 mm, about a quarter of the frame width.
        val ok = passRate(10) { val q = quad(600.0, 450.0, 260.0, 164.0, jitter = 12.0); scene(q, 856, 540, Bg.DARK_WOOD) to q }
        assertTrue("ID cards: $ok/10", ok >= 9)
    }

    @Test fun openBookTwoPagesWithGutter() {
        val ok = passRate(10) { val q = quad(600.0, 450.0, 500.0, 360.0, jitter = 20.0); scene(q, 1400, 1000, Bg.DARK_WOOD, gutter = true) to q }
        assertTrue("books: $ok/10", ok >= 9)
    }

    @Test fun lowLight() {
        val ok = passRate(10) { val q = quad(600.0, 450.0, 300.0, 400.0); scene(q, 850, 1100, Bg.DARK_WOOD, light = 0.35, noise = 10.0) to q }
        assertTrue("low light: $ok/10", ok >= 8)
    }

    @Test fun blurryPhoto() {
        val ok = passRate(10) { val q = quad(600.0, 450.0, 300.0, 400.0); scene(q, 850, 1100, Bg.DARK_WOOD, blurSigma = 4.0) to q }
        assertTrue("blur: $ok/10", ok >= 8)
    }

    @Test fun emptyClutterIsNotADocument() {
        val falsePositives = (1..20).count {
            val bg = scene(listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)), 2, 2, Bg.CLUTTER, shadow = false)
            val q = EdgeDetector.detect(gray(bg)).quad
            if (q != null) println("FALSE POSITIVE area=${q.area()} quad=${q.points}")
            q != null
        }
        assertEquals(0, falsePositives)
    }

    @Test fun blurLowersSharpnessScoreUsedByAutoCapture() {
        val q = quad(600.0, 450.0, 300.0, 400.0, 0.0)
        val sharp = EdgeDetector.detect(gray(scene(q, 850, 1100, Bg.DARK_WOOD, blurSigma = 0.5))).sharpness
        val blurry = EdgeDetector.detect(gray(scene(q, 850, 1100, Bg.DARK_WOOD, blurSigma = 5.0))).sharpness
        assertTrue("sharp=$sharp blurry=$blurry", blurry < sharp / 3)
    }

    @Test fun blankFrameFindsNothing() {
        assertNull(EdgeDetector.detect(Mat(480, 640, CvType.CV_8UC1, Scalar(128.0))).quad)
    }

    // ------------------------------------------------------------ enhancement filters

    @Test fun cleanFlattensShadowAndKeepsInkDark() {
        val q = listOf(Point(0.0, 0.0), Point(W.toDouble(), 0.0), Point(W.toDouble(), H.toDouble()), Point(0.0, H.toDouble()))
        val page = scene(q, 850, 1100, Bg.DARK_WOOD, shadow = true)
        val out = FilterEngine.apply(page, Filter.CLEAN)
        val g = gray(out)
        val left = g.submat(0, H, 0, W / 5); val right = g.submat(0, H, W - W / 5, W)
        val p90 = { m: Mat -> percentile(m, 0.9) }
        assertTrue("paper evened out: ${p90(left)} vs ${p90(right)}", kotlin.math.abs(p90(left) - p90(right)) <= 6)
        assertTrue("paper is white", p90(left) >= 235)
        assertTrue("ink stays dark", percentile(g, 0.05) <= 90)
    }

    @Test fun blackAndWhiteIsBinary() {
        val page = scene(listOf(Point(0.0, 0.0), Point(W.toDouble(), 0.0), Point(W.toDouble(), H.toDouble()), Point(0.0, H.toDouble())), 850, 1100, Bg.DARK_WOOD)
        val bw = FilterEngine.apply(page, Filter.BW)
        assertEquals(1, bw.channels())
        val hist = IntArray(256); val data = ByteArray(bw.total().toInt()); bw.get(0, 0, data)
        data.forEach { hist[it.toInt() and 0xFF]++ }
        assertEquals(data.size, hist[0] + hist[255])
    }

    @Test fun autoKeepsColourStampsAndDropsGrayPages() {
        val full = listOf(Point(0.0, 0.0), Point(W.toDouble(), 0.0), Point(W.toDouble(), H.toDouble()), Point(0.0, H.toDouble()))
        val plain = scene(full, 850, 1100, Bg.DARK_WOOD, shadow = false)
        assertEquals(1, FilterEngine.apply(plain, Filter.AUTO).channels())
        val stamped = plain.clone()
        Imgproc.circle(stamped, Point(900.0, 700.0), 90, Scalar(200.0, 40.0, 40.0), 10)
        Imgproc.putText(stamped, "PAID", Point(830.0, 715.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(200.0, 40.0, 40.0), 5)
        assertEquals(3, FilterEngine.apply(stamped, Filter.AUTO).channels())
    }

    @Test fun everyFilterHandlesTinyAndHugeImages() {
        for (size in listOf(Size(20.0, 30.0), Size(4000.0, 3000.0))) {
            val m = Mat(size, CvType.CV_8UC3, Scalar(200.0, 200.0, 200.0))
            Filter.entries.forEach { f -> val o = FilterEngine.apply(m, f); assertEquals(m.size(), o.size()); o.release() }
        }
    }

    private fun percentile(m: Mat, p: Double): Int {
        val data = ByteArray((m.total() * m.channels()).toInt()); m.get(0, 0, data)
        val hist = IntArray(256); data.forEach { hist[it.toInt() and 0xFF]++ }
        var acc = 0; val target = (data.size * p).toInt()
        for (i in 0 until 256) { acc += hist[i]; if (acc >= target) return i }
        return 255
    }

    @Suppress("unused") private fun np(x: Double, y: Double) = NPoint(x.toFloat(), y.toFloat())
}
