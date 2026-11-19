package com.docscanner.app.imaging

import com.docscanner.app.model.Filter
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Enhancement filters (architecture.md 4.3). Input: 8-bit RGB (CV_8UC3). Output: a new Mat,
 * CV_8UC3 for colour filters and CV_8UC1 for gray and black-and-white. The caller releases both.
 */
object FilterEngine {

    fun apply(rgb: Mat, filter: Filter): Mat = when (filter) {
        Filter.ORIGINAL -> rgb.clone()
        Filter.AUTO -> if (colourfulness(rgb) < AUTO_COLOUR_THRESHOLD) gray(rgb) else clean(rgb)
        Filter.CLEAN -> clean(rgb)
        Filter.GRAY -> gray(rgb)
        Filter.BW -> blackWhite(rgb, soft = false)
        Filter.BW_SOFT -> blackWhite(rgb, soft = true)
    }

    /** Whitens the paper, keeps ink and colour: flatten lighting on L, stretch, sharpen, lift chroma. */
    private fun clean(rgb: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val l = channels[0]
        val flat = flattenIllumination(l)
        val stretched = stretch(flat, 0.01, 0.99)
        flat.release()
        val sharp = unsharp(stretched)
        stretched.release()
        // chroma boost: (c - 128) * 1.15 + 128
        for (i in 1..2) {
            channels[i].convertTo(channels[i], -1, 1.15, -128 * 0.15)
        }
        l.release()
        channels[0] = sharp
        Core.merge(channels, lab)
        channels.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return out
    }

    private fun gray(rgb: Mat): Mat {
        val g = toGray(rgb)
        val flat = flattenIllumination(g)
        g.release()
        val out = stretch(flat, 0.01, 0.99)
        flat.release()
        return out
    }

    private fun blackWhite(rgb: Mat, soft: Boolean): Mat {
        val g = toGray(rgb)
        val flat = flattenIllumination(g)
        g.release()
        val width = max(flat.cols(), flat.rows())
        var block = (width / if (soft) 20 else 40) or 1
        block = block.coerceIn(15, 255)
        val out = Mat()
        Imgproc.adaptiveThreshold(
            flat, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
            block, if (soft) 6.0 else 12.0,
        )
        flat.release()
        if (!soft) {
            // Despeckle isolated dots without eating thin strokes.
            Imgproc.medianBlur(out, out, 3)
        }
        return out
    }

    private fun toGray(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        return g
    }

    /**
     * Divides the image by an estimate of its background (a closed + median-blurred copy at low resolution),
     * which removes shadows and uneven light. Input and output: CV_8UC1.
     */
    fun flattenIllumination(gray: Mat): Mat {
        val longEdge = max(gray.cols(), gray.rows())
        val scale = (512.0 / longEdge).coerceAtMost(1.0)
        val small = Mat()
        Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val k = ((max(small.cols(), small.rows()) / 25) or 1).coerceAtLeast(9).toDouble()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k, k))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        val out = Mat()
        Core.divide(gray, bg, out, 255.0)
        bg.release()
        return out
    }

    /** Linear contrast stretch so the [lo]..[hi] percentiles map to 0..255. CV_8UC1 in and out. */
    fun stretch(gray: Mat, lo: Double, hi: Double): Mat {
        val hist = IntArray(256)
        val data = ByteArray((gray.total()).toInt())
        gray.get(0, 0, data)
        for (b in data) hist[b.toInt() and 0xFF]++
        val total = data.size.toDouble()
        var acc = 0.0
        var low = 0
        var high = 255
        var lowSet = false
        for (i in 0 until 256) {
            acc += hist[i]
            if (!lowSet && acc / total >= lo) { low = i; lowSet = true }
            if (acc / total >= hi) { high = i; break }
        }
        val out = Mat()
        if (high - low < 10) {
            gray.copyTo(out)
            return out
        }
        val alpha = 255.0 / (high - low)
        gray.convertTo(out, -1, alpha, -low * alpha)
        return out
    }

    private fun unsharp(src: Mat): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), 1.5)
        val out = Mat()
        Core.addWeighted(src, 1.5, blur, -0.5, 0.0, out)
        blur.release()
        return out
    }

    /**
     * Measured on test scans: black-ink pages score 2.7-4.7, a page with one red stamp 11-12.
     * Below this the page is treated as gray; above it colour is kept.
     */
    private const val AUTO_COLOUR_THRESHOLD = 8.0

    /** Hasler-Suesstrunk colourfulness on a small copy. */
    fun colourfulness(rgb: Mat): Double {
        val small = Mat()
        val scale = (256.0 / max(rgb.cols(), rgb.rows())).coerceAtMost(1.0)
        Imgproc.resize(rgb, small, Size(rgb.cols() * scale, rgb.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val f = Mat()
        small.convertTo(f, CvType.CV_32FC3)
        small.release()
        val ch = ArrayList<Mat>(3)
        Core.split(f, ch)
        f.release()
        val rg = Mat()
        Core.subtract(ch[0], ch[1], rg)
        val yb = Mat()
        Core.add(ch[0], ch[1], yb)
        Core.multiply(yb, org.opencv.core.Scalar(0.5), yb)
        Core.subtract(yb, ch[2], yb)
        val (mRg, sRg) = meanStd(rg)
        val (mYb, sYb) = meanStd(yb)
        ch.forEach { it.release() }
        rg.release()
        yb.release()
        return sqrt(sRg * sRg + sYb * sYb) + 0.3 * sqrt(mRg * mRg + mYb * mYb)
    }

    private fun meanStd(m: Mat): Pair<Double, Double> {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(m, mean, std)
        val r = (mean.toArray().firstOrNull() ?: 0.0) to (std.toArray().firstOrNull() ?: 0.0)
        mean.release()
        std.release()
        return r
    }

    /** Brightness (-100..100) and contrast (-100..100) as a final linear transform. */
    fun adjust(src: Mat, brightness: Int, contrast: Int): Mat {
        val out = Mat()
        if (brightness == 0 && contrast == 0) {
            src.copyTo(out)
            return out
        }
        val alpha = 1.0 + contrast / 100.0
        val beta = (brightness * 1.28).roundToInt().toDouble() + 128 * (1 - alpha)
        src.convertTo(out, -1, alpha, beta)
        return out
    }
}
