package com.docscanner.app

import com.docscanner.app.model.NPoint
import com.docscanner.app.model.Quad
import com.docscanner.app.imaging.EdgeState
import com.docscanner.app.imaging.QuadTracker
import com.docscanner.app.util.OrderKey
import com.docscanner.app.util.Titles
import com.docscanner.app.util.UuidV7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OrderKeyTest {
    @Test
    fun appendsStayOrderedAndShort() {
        var last: String? = null
        repeat(2000) {
            val k = OrderKey.after(last)
            if (last != null) assertTrue("$k > $last", k > last!!)
            last = k
        }
        assertEquals(6, last!!.length)
    }

    @Test
    fun randomInsertsAlwaysFitBetweenNeighbours() {
        val rnd = Random(42)
        val keys = mutableListOf<String>()
        repeat(3000) {
            val i = if (keys.isEmpty()) 0 else rnd.nextInt(keys.size + 1)
            val a = keys.getOrNull(i - 1)
            val b = keys.getOrNull(i)
            val k = OrderKey.between(a, b)
            if (a != null) assertTrue("$a < $k", a < k)
            if (b != null) assertTrue("$k < $b", k < b)
            assertFalse(k.endsWith("0"))
            keys.add(i, k)
        }
        assertEquals(keys.sorted(), keys)
    }
}

class QuadTest {
    private val square = Quad(NPoint(0.1f, 0.1f), NPoint(0.9f, 0.1f), NPoint(0.9f, 0.9f), NPoint(0.1f, 0.9f))

    @Test
    fun serializeRoundTrip() {
        assertEquals(square, Quad.parse(square.serialize()))
        assertNull(Quad.parse("garbage"))
        assertNull(Quad.parse(null))
    }

    @Test
    fun validity() {
        assertTrue(square.isValid())
        // Swap two corners: self-intersecting "bow tie".
        assertFalse(square.copy(tr = square.br, br = square.tr).isValid())
        assertEquals(0.64f, square.area(), 1e-4f)
    }

    @Test
    fun ordersUnorderedPoints() {
        val shuffled = listOf(square.br, square.tl, square.bl, square.tr)
        assertEquals(square, Quad.fromUnordered(shuffled))
    }
}

class QuadTrackerTest {
    private val q = Quad(NPoint(0.2f, 0.2f), NPoint(0.8f, 0.2f), NPoint(0.8f, 0.8f), NPoint(0.2f, 0.8f))

    @Test
    fun locksAfterHoldAndDoesNotRecaptureSamePage() {
        val t = QuadTracker(holdMs = 600)
        assertTrue(t.update(q, 100.0, 0) is EdgeState.Detected)
        assertTrue(t.update(q, 100.0, 300) is EdgeState.Detected)
        assertTrue(t.update(q, 100.0, 700) is EdgeState.Locked)
        t.onCaptured()
        // Same page held still: must not lock again.
        assertTrue(t.update(q, 100.0, 2000) is EdgeState.Detected)
        assertTrue(t.update(q, 100.0, 4000) is EdgeState.Detected)
    }

    @Test
    fun blurryFramesNeverLock() {
        val t = QuadTracker(holdMs = 600, minSharpness = 50.0)
        t.update(q, 10.0, 0)
        assertTrue(t.update(q, 10.0, 1000) is EdgeState.Detected)
    }

    @Test
    fun losingThePageResets() {
        val t = QuadTracker(missTolerance = 2)
        t.update(q, 100.0, 0)
        repeat(3) { t.update(null, 0.0, 10) }
        assertNull(t.current)
    }
}

class UtilTest {
    @Test
    fun uuidV7Format() {
        val a = UuidV7.next(1_700_000_000_000)
        assertTrue(a.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertTrue(UuidV7.next(1_700_000_000_000) < UuidV7.next(1_700_000_001_000))
    }

    @Test
    fun safeFileNames() {
        assertEquals("Tax_ invoice_2026", Titles.safeFileName("Tax/ invoice:2026"))
        assertEquals("Scan", Titles.safeFileName("   "))
        assertNotNull(Titles.defaultTitle(0))
    }
}
