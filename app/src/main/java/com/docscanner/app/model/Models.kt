package com.docscanner.app.model

import kotlin.math.abs
import kotlin.math.hypot

/** A point in normalised image space: 0..1 on both axes, origin top-left of the upright image. */
data class NPoint(val x: Float, val y: Float)

/**
 * A page quad: four corners, clockwise from top-left, normalised to the upright original.
 */
data class Quad(val tl: NPoint, val tr: NPoint, val br: NPoint, val bl: NPoint) {
    val points: List<NPoint> get() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: NPoint): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    /** Largest corner movement against [other], in normalised units. */
    fun maxDistanceTo(other: Quad): Float =
        points.zip(other.points).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    /** Shoelace area in normalised units (1.0 = whole frame). */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** Convex and not self-intersecting: every turn has the same sign. */
    fun isValid(minArea: Float = 0.02f): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return area() >= minArea
    }

    fun serialize(): String = points.joinToString(";") { "${it.x},${it.y}" }

    companion object {
        val FULL = Quad(NPoint(0f, 0f), NPoint(1f, 0f), NPoint(1f, 1f), NPoint(0f, 1f))

        fun parse(s: String?): Quad? {
            if (s.isNullOrBlank()) return null
            val pts = s.split(";").mapNotNull { pair ->
                val xy = pair.split(",")
                if (xy.size != 2) null else {
                    val x = xy[0].toFloatOrNull()
                    val y = xy[1].toFloatOrNull()
                    if (x == null || y == null) null else NPoint(x, y)
                }
            }
            return if (pts.size == 4) Quad(pts[0], pts[1], pts[2], pts[3]) else null
        }

        /** Orders four arbitrary points as tl, tr, br, bl (by x+y and y-x). */
        fun fromUnordered(pts: List<NPoint>): Quad {
            require(pts.size == 4)
            val tl = pts.minBy { it.x + it.y }
            val br = pts.maxBy { it.x + it.y }
            val tr = pts.minBy { it.y - it.x }
            val bl = pts.maxBy { it.y - it.x }
            return Quad(tl, tr, br, bl)
        }
    }
}

enum class Filter(val id: String) {
    ORIGINAL("original"), AUTO("auto"), CLEAN("clean"), GRAY("gray"), BW("bw"), BW_SOFT("bw_soft");

    companion object {
        fun fromId(id: String?): Filter = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class QuadSource(val id: String) {
    AUTO("auto"), MANUAL("manual"), FULL("full");

    companion object {
        fun fromId(id: String?): QuadSource = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class ScanMode(val id: String) {
    SINGLE("single"), BATCH("batch");

    companion object {
        fun fromId(id: String?): ScanMode = entries.firstOrNull { it.id == id } ?: BATCH
    }
}

/** Quality profiles from architecture.md 4.2. */
enum class QualityProfile(val id: String, val longEdge: Int, val jpegQuality: Int) {
    SMALL("small", 2000, 75),
    BALANCED("balanced", 3508, 85),
    ORIGINAL("original", 6000, 92);

    companion object {
        fun fromId(id: String?): QualityProfile = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

enum class PageSize(val id: String, val widthPt: Int, val heightPt: Int) {
    FIT("fit", 0, 0), A4("a4", 595, 842), LETTER("letter", 612, 792);

    companion object {
        fun fromId(id: String?): PageSize = entries.firstOrNull { it.id == id } ?: FIT
    }
}

enum class DocStatus(val id: String) { DRAFT("draft"), READY("ready") }

enum class RenderState(val id: String) { PENDING("pending"), RENDERING("rendering"), DONE("done"), FAILED("failed") }

data class Page(
    val id: String,
    val documentId: String,
    val orderKey: String,
    val originalSha: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val sourceRotation: Int,
    val quad: Quad?,
    val quadSource: QuadSource,
    val rotation: Int,
    val filter: Filter,
    val processedSha: String?,
    val thumbSha: String?,
    val renderState: RenderState,
    val version: Int,
)

data class DocumentSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val coverThumbSha: String?,
    val updatedAt: Long,
    val createdAt: Long,
    val status: DocStatus,
)

data class DocumentDetail(
    val summary: DocumentSummary,
    val pages: List<Page>,
)
