package com.docscanner.app

import com.docscanner.app.cloud.sync.SyncMerge
import com.docscanner.app.cloud.sync.VaultCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultCryptoTest {
    private val fastIterations = 1_000 // production uses 310k; the maths is identical

    @Test
    fun unlockWithRightPassphraseOnly() {
        val (key, vault) = VaultCrypto.newVault("correct horse".toCharArray(), fastIterations)
        assertArrayEquals(key.raw, VaultCrypto.unlock("correct horse".toCharArray(), vault).raw)
        try {
            VaultCrypto.unlock("wrong horse".toCharArray(), vault)
            fail("wrong passphrase must not unlock")
        } catch (_: VaultCrypto.WrongPassphraseException) {
        }
    }

    @Test
    fun changingPassphraseKeepsTheSameKey() {
        val (key, _) = VaultCrypto.newVault("old".toCharArray(), fastIterations)
        val rewrapped = VaultCrypto.changePassphrase(key, "new".toCharArray(), fastIterations)
        assertArrayEquals(key.raw, VaultCrypto.unlock("new".toCharArray(), rewrapped).raw)
    }

    @Test
    fun encryptionIsAuthenticatedAndBoundToItsId() {
        val (key, _) = VaultCrypto.newVault("p".toCharArray(), fastIterations)
        val data = "page bytes".toByteArray()
        val c = VaultCrypto.encrypt(key, data, "uid1/blob/abc")
        assertArrayEquals(data, VaultCrypto.decrypt(key, c, "uid1/blob/abc"))
        assertNotEquals(String(data), String(c))
        expectCorrupt { VaultCrypto.decrypt(key, c, "uid1/blob/OTHER") }        // swapped id
        val tampered = c.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        expectCorrupt { VaultCrypto.decrypt(key, tampered, "uid1/blob/abc") }   // flipped bit
        val (otherKey, _) = VaultCrypto.newVault("p".toCharArray(), fastIterations)
        expectCorrupt { VaultCrypto.decrypt(otherKey, c, "uid1/blob/abc") }     // someone else's key
    }

    @Test
    fun objectNamesHideHashesAndDependOnTheKey() {
        val (k1, _) = VaultCrypto.newVault("a".toCharArray(), fastIterations)
        val (k2, _) = VaultCrypto.newVault("a".toCharArray(), fastIterations)
        val sha = "a".repeat(64)
        assertEquals(VaultCrypto.objectName(k1, sha), VaultCrypto.objectName(k1, sha))
        assertNotEquals(VaultCrypto.objectName(k1, sha), VaultCrypto.objectName(k2, sha))
        assertTrue(!VaultCrypto.objectName(k1, sha).contains(sha))
    }

    private fun expectCorrupt(block: () -> Unit) {
        try {
            block(); fail("expected CorruptDataException")
        } catch (_: VaultCrypto.CorruptDataException) {
        }
    }
}

class SyncMergeTest {
    private fun obj(vararg p: Pair<String, Any>) = JsonObject(p.associate { (k, v) ->
        k to when (v) { is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> error("") }
    })

    @Test
    fun differentFieldsBothSurvive() {
        val base = obj("title" to "Scan", "filter" to "auto", "deleted" to false, "updatedAt" to 1)
        val local = obj("title" to "Tax 2026", "filter" to "auto", "deleted" to false, "updatedAt" to 5)
        val remote = obj("title" to "Scan", "filter" to "bw", "deleted" to false, "updatedAt" to 3)
        val r = SyncMerge.merge(base, local, remote)
        assertEquals(JsonPrimitive("Tax 2026"), r.merged["title"])
        assertEquals(JsonPrimitive("bw"), r.merged["filter"])
        assertEquals(0, r.conflicts)
        assertEquals(JsonPrimitive(5L), r.merged["updatedAt"])
    }

    @Test
    fun sameFieldNewerWinsAndIsCounted() {
        val base = obj("title" to "Scan", "updatedAt" to 1)
        val local = obj("title" to "Local", "updatedAt" to 2)
        val remote = obj("title" to "Remote", "updatedAt" to 9)
        val r = SyncMerge.merge(base, local, remote)
        assertEquals(JsonPrimitive("Remote"), r.merged["title"])
        assertEquals(1, r.conflicts)
    }

    @Test
    fun tieIsDeterministicByDevice() {
        val base = obj("title" to "Scan", "updatedAt" to 1)
        val a = obj("title" to "A", "updatedAt" to 5)
        val b = obj("title" to "B", "updatedAt" to 5)
        val onA = SyncMerge.merge(base, a, b, localDevice = "dev-a", remoteDevice = "dev-b").merged["title"]
        val onB = SyncMerge.merge(base, b, a, localDevice = "dev-b", remoteDevice = "dev-a").merged["title"]
        assertEquals(onA, onB) // both phones converge on the same value
    }

    @Test
    fun editBeatsConcurrentDelete() {
        val base = obj("title" to "Scan", "deleted" to false, "updatedAt" to 1)
        val local = obj("title" to "Renamed", "deleted" to false, "updatedAt" to 2)
        val remote = obj("title" to "Scan", "deleted" to true, "updatedAt" to 9)
        val r = SyncMerge.merge(base, local, remote)
        assertEquals(JsonPrimitive(false), r.merged["deleted"])
        assertEquals(JsonPrimitive("Renamed"), r.merged["title"])
        assertEquals(1, r.conflicts)
    }

    @Test
    fun plainDeleteIsApplied() {
        val base = obj("title" to "Scan", "deleted" to false, "updatedAt" to 1)
        val local = obj("title" to "Scan", "deleted" to false, "updatedAt" to 1)
        val remote = obj("title" to "Scan", "deleted" to true, "updatedAt" to 9)
        val r = SyncMerge.merge(base, local, remote)
        assertEquals(JsonPrimitive(true), r.merged["deleted"])
        assertEquals(0, r.conflicts)
    }

    @Test
    fun codecRoundTrip() {
        val o = obj("title" to "বাংলা নাম", "rev" to 3, "deleted" to false)
        assertEquals(o, SyncMerge.decode(SyncMerge.encode(o)))
    }
}
