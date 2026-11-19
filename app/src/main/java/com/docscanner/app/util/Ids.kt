package com.docscanner.app.util

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UUID v7: 48-bit Unix ms timestamp + random. Time-ordered, safe to use as a global sync id later. */
object UuidV7 {
    private val random = SecureRandom()

    fun next(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()   // version 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()   // variant 10
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/**
 * Fractional ordering keys (base-62 strings compared lexicographically). Moving a page writes one row,
 * and a key strictly between any two neighbours always exists. Port of the well-known midpoint
 * algorithm; appends use fixed-width keys so they stay short. Tested in OrderKeyTest.
 */
object OrderKey {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62
    private const val WIDTH = 5

    /** A key strictly between [a] and [b]. null = open end. */
    fun between(a: String?, b: String?): String {
        require(a == null || b == null || a < b) { "order keys out of order: $a !< $b" }
        return midpoint(a ?: "", b)
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            var n = 0
            while ((if (n < a.length) a[n] else '0') == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.substring(minOf(n, a.length)), b.substring(n))
        }
        val da = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val db = if (b != null) DIGITS.indexOf(b[0]) else BASE
        if (db - da > 1) {
            return DIGITS[Math.round(0.5 * (da + db)).toInt()].toString()
        }
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[da] + midpoint(if (a.isNotEmpty()) a.substring(1) else "", null)
    }

    /** A key after [last], fixed width so long batches do not grow keys. */
    fun after(last: String?): String {
        if (last == null) return encode(1000L) + "V"
        val prefix = last.padEnd(WIDTH, '0').substring(0, WIDTH)
        return encode(decode(prefix) + 1) + "V"
    }

    private fun decode(s: String): Long = s.fold(0L) { acc, c -> acc * BASE + DIGITS.indexOf(c) }

    private fun encode(value: Long): String {
        var v = value
        val chars = CharArray(WIDTH)
        for (i in WIDTH - 1 downTo 0) {
            chars[i] = DIGITS[(v % BASE).toInt()]
            v /= BASE
        }
        return String(chars)
    }
}

object Titles {
    /** "Scan 19 Nov 2026 18:05", device zone, generated once at creation. */
    fun defaultTitle(nowMs: Long, locale: Locale = Locale.getDefault()): String =
        "Scan " + SimpleDateFormat("d MMM yyyy HH:mm", locale).format(Date(nowMs))

    /** File-system-safe version of a document title. */
    fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Scan" }.take(80)
}
