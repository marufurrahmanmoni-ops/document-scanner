package com.docscanner.app.imaging

import com.docscanner.app.model.NPoint
import com.docscanner.app.model.Quad

/** What the camera overlay shows. */
sealed interface EdgeState {
    data object Searching : EdgeState
    data class Detected(val quad: Quad) : EdgeState
    data class Locked(val quad: Quad) : EdgeState
}

/**
 * Smooths detected quads across frames and decides when the page is steady enough to auto-capture
 * (architecture.md 4.1): every corner moves less than 1.5% of the frame for [holdMs], and the frame is sharp.
 * After a capture it re-arms only once the page moves away or disappears, so one page is not captured twice.
 */
class QuadTracker(
    private val holdMs: Long = 600,
    private val stableThreshold: Float = 0.015f,
    private val rearmThreshold: Float = 0.10f,
    private val minSharpness: Double = 25.0,
    private val missTolerance: Int = 4,
) {
    private var smoothed: Quad? = null
    private var stableSince: Long = 0
    private var misses = 0
    private var armed = true
    private var lastCaptured: Quad? = null

    val current: Quad? get() = smoothed

    fun update(raw: Quad?, sharpness: Double, nowMs: Long): EdgeState {
        if (raw == null) {
            misses++
            if (misses > missTolerance) {
                smoothed = null
                stableSince = 0
                armed = true
                lastCaptured = null
            }
            return smoothed?.let { EdgeState.Detected(it) } ?: EdgeState.Searching
        }
        misses = 0
        val prev = smoothed
        val next = if (prev == null) raw else blend(prev, raw, 0.5f)
        if (prev == null || prev.maxDistanceTo(raw) > stableThreshold || sharpness < minSharpness) {
            stableSince = nowMs
        }
        smoothed = next
        lastCaptured?.let { if (it.maxDistanceTo(next) > rearmThreshold) armed = true }
        val steady = nowMs - stableSince >= holdMs
        return if (steady && armed) EdgeState.Locked(next) else EdgeState.Detected(next)
    }

    /** Called after an auto or manual capture. */
    fun onCaptured() {
        armed = false
        lastCaptured = smoothed
        stableSince = Long.MAX_VALUE / 2
    }

    fun reset() {
        smoothed = null
        stableSince = 0
        misses = 0
        armed = true
        lastCaptured = null
    }

    private fun blend(a: Quad, b: Quad, t: Float): Quad {
        fun mix(p: NPoint, q: NPoint) = NPoint(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
        return Quad(mix(a.tl, b.tl), mix(a.tr, b.tr), mix(a.br, b.br), mix(a.bl, b.bl))
    }
}
