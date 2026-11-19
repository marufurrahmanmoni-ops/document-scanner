package com.docscanner.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscanner.app.data.db.PageEntity
import com.docscanner.app.model.DocStatus
import com.docscanner.app.model.RenderState
import com.docscanner.app.storage.BlobStore
import com.docscanner.app.support.TestApp
import com.docscanner.app.support.TestSupport
import com.docscanner.app.util.OrderKey
import com.docscanner.app.util.UuidV7
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Data layer on a real Room database (Robolectric, in-memory) and a real encrypted BlobStore.
 * Pages are inserted through the DAO with a finished render so no OpenCV is needed here (imaging has its own tests).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class DataLayerTest {

    private lateinit var context: Context
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        c = TestSupport.container(context)
    }

    /** A saved page with a real (encrypted) original blob. */
    private suspend fun addPage(docId: String, content: String = UuidV7.next()): PageEntity {
        val sha = c.blobs.put(content.toByteArray())
        val now = System.currentTimeMillis()
        val page = PageEntity(
            id = UuidV7.next(), documentId = docId, orderKey = OrderKey.after(c.database.pages().lastOrderKey(docId)),
            originalPath = sha, originalSha = sha, originalWidth = 1000, originalHeight = 1400, source = "camera",
            processedSha = sha, processedPath = sha, thumbPath = sha, renderState = RenderState.DONE.id,
            createdAt = now, updatedAt = now,
        )
        c.database.pages().insert(page)
        c.database.sync().recountPages(docId)
        return page
    }

    private suspend fun savedDoc(pages: Int = 3): Pair<String, List<PageEntity>> {
        val id = c.documents.createDraft()
        val list = (1..pages).map { addPage(id) }
        c.documents.saveDocument(id, "Tax invoice 2026")
        return id to list
    }

    // ------------------------------------------------------------ F01/F02 drafts and saving

    @Test fun draftIsInvisibleUntilSaved() = runBlocking {
        val id = c.documents.createDraft()
        assertNull("an empty draft is not offered for resume", c.documents.observeLatestDraft().first())
        addPage(id)
        assertEquals(id, c.documents.observeLatestDraft().first()?.id)
        assertTrue("drafts never appear in the library", c.documents.observeAll(byName = false).first().isEmpty())
        c.documents.saveDocument(id, "  Lease  ")
        val all = c.documents.observeAll(byName = false).first()
        assertEquals(1, all.size)
        assertEquals("Lease", all.first().title)
        assertEquals(1, all.first().pageCount)
        assertNull(c.documents.observeLatestDraft().first())
    }

    @Test fun blankTitleKeepsAutoTitle() = runBlocking {
        val id = c.documents.createDraft()
        addPage(id)
        val auto = c.documents.document(id)!!.title
        c.documents.saveDocument(id, "   ")
        assertEquals(auto, c.documents.document(id)!!.title)
        assertTrue(auto.startsWith("Scan "))
    }

    @Test fun longAndBengaliTitles() = runBlocking {
        val (id, _) = savedDoc(1)
        c.documents.rename(id, "জমির দলিল ২০২৬ — " + "x".repeat(300))
        val t = c.documents.document(id)!!.title
        assertTrue(t.startsWith("জমির দলিল ২০২৬"))
        assertEquals(200, t.length)
    }

    @Test fun discardDraftRemovesPagesToo() = runBlocking {
        val id = c.documents.createDraft()
        addPage(id); addPage(id)
        c.documents.discardDraft(id)
        assertNull(c.documents.document(id))
        assertTrue(c.database.pages().livePages(id).isEmpty())
    }

    @Test fun discardNeverDeletesASavedDocument() = runBlocking {
        val (id, _) = savedDoc(2)
        c.documents.discardDraft(id)
        assertNotNull(c.documents.document(id))
    }

    // ------------------------------------------------------------ F02 multi-page editing

    @Test fun reorderChangesOneRowAndKeepsOrderUnique() = runBlocking {
        val (id, pages) = savedDoc(4)
        c.documents.movePage(pages[3].id, -1)
        c.documents.movePage(pages[3].id, -1)
        c.documents.movePage(pages[0].id, +1)
        val order = c.database.pages().livePages(id).map { it.id }
        assertEquals(listOf(pages[1].id, pages[0].id, pages[3].id, pages[2].id), order)
        assertEquals(order.size, c.database.pages().livePages(id).map { it.orderKey }.toSet().size)
        // Edges are no-ops, not crashes.
        c.documents.movePage(order.first(), -1)
        c.documents.movePage(order.last(), +1)
        assertEquals(order, c.database.pages().livePages(id).map { it.id })
    }

    @Test fun deletePageIsSoftAndUpdatesCount() = runBlocking {
        val (id, pages) = savedDoc(3)
        c.documents.deletePage(pages[1].id)
        assertEquals(2, c.database.pages().livePages(id).size)
        assertEquals(2, c.documents.document(id)!!.pageCount)
        assertNotNull("row kept as a tombstone for sync", c.database.pages().get(pages[1].id)?.deletedAt)
    }

    @Test fun pageAddedAfterADeleteGoesLast() = runBlocking {
        val (id, pages) = savedDoc(2)
        c.documents.deletePage(pages[1].id)
        val added = addPage(id)
        assertEquals(listOf(pages[0].id, added.id), c.database.pages().livePages(id).map { it.id })
    }

    // ------------------------------------------------------------ library: trash / restore

    @Test fun trashAndRestore() = runBlocking {
        val (id, _) = savedDoc(1)
        c.documents.trashDocument(id)
        assertTrue(c.documents.observeAll(false).first().isEmpty())
        assertTrue(c.documents.observeRecent(6).first().isEmpty())
        c.documents.restoreDocument(id)
        assertEquals(1, c.documents.observeAll(false).first().size)
    }

    @Test fun sortByNameIgnoresCase() = runBlocking {
        for (t in listOf("beta", "Alpha", "gamma")) {
            val id = c.documents.createDraft(); addPage(id); c.documents.saveDocument(id, t)
        }
        assertEquals(listOf("Alpha", "beta", "gamma"), c.documents.observeAll(byName = true).first().map { it.title })
    }

    // ------------------------------------------------------------ sync outbox (offline-first)

    @Test fun everyChangeIsQueuedForSync() = runBlocking {
        val (id, pages) = savedDoc(2)
        c.documents.rename(id, "Renamed")
        c.documents.deletePage(pages[0].id)
        val pending = c.database.outbox().pending(100).map { it.entity to it.entityId }.toSet()
        assertTrue("document" to id in pending)
        assertTrue("page" to pages[0].id in pending)
    }

    // ------------------------------------------------------------ BUG-001 regression: remote apply must not destroy pages

    @Test fun applyingARemoteDocumentUpdateKeepsItsPages() = runBlocking {
        val (id, _) = savedDoc(3)
        val doc = c.documents.document(id)!!
        // What the sync engine does when another phone renamed this document:
        c.database.sync().upsertDocument(doc.copy(title = "Renamed elsewhere", version = doc.version + 1))
        assertEquals("Renamed elsewhere", c.documents.document(id)!!.title)
        assertEquals("pages must survive a remote rename", 3, c.database.pages().livePages(id).size)
    }

    // ------------------------------------------------------------ housekeeping

    @Test fun housekeepingKeepsReferencedBlobsAndPurgesOldTrash() = runBlocking {
        val (keep, _) = savedDoc(1)
        val (old, _) = savedDoc(1)
        val orphan = c.blobs.put("orphan".toByteArray())
        // Age the orphan blob and the trashed document past their limits.
        File(File(File(context.cacheDir.listFiles()!!.first { it.name.startsWith("test-blobs") }, "blobs"), orphan.take(2)), "$orphan.bin")
            .setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        c.documents.trashDocument(old)
        val d = c.documents.document(old)!!
        c.database.documents().update(d.copy(deletedAt = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000))
        c.documents.housekeeping()
        assertNotNull(c.documents.document(keep))
        assertNull("trash older than 30 days is purged", c.documents.document(old))
        assertFalse("unreferenced old blob is deleted", c.blobs.exists(orphan))
        val keptPage = c.database.pages().livePages(keep).first()
        assertTrue("referenced blob kept", c.blobs.exists(keptPage.originalSha))
    }

    @Test fun eraseEverythingLeavesNothing() = runBlocking {
        val (id, pages) = savedDoc(2)
        c.documents.eraseEverything()
        assertNull(c.documents.document(id))
        assertFalse(c.blobs.exists(pages[0].originalSha))
    }

    // ------------------------------------------------------------ encrypted storage + storage limits

    @Test fun blobsAreEncryptedAtRestAndTamperEvident() {
        val root = File(context.cacheDir, "blobs-tamper")
        val store = BlobStore(root, TestSupport.testAead())
        val secret = "PASSPORT NO A1234567".toByteArray()
        val sha = store.put(secret)
        val file = File(File(File(root, "blobs"), sha.take(2)), "$sha.bin")
        assertFalse("plaintext must not be on disk", String(file.readBytes(), Charsets.ISO_8859_1).contains("PASSPORT"))
        assertEquals(String(secret), String(store.read(sha)))
        val bytes = file.readBytes(); bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x01).toByte(); file.writeBytes(bytes)
        try {
            store.read(sha); fail("tampered blob must not decrypt")
        } catch (_: Exception) {
        }
    }

    @Test fun storageFullFailsLoudlyAndLeavesNoPartialFile() {
        val root = File(context.cacheDir, "blobs-full")
        val store = BlobStore(root, TestSupport.testAead())
        File(root, "blobs").setWritable(false)
        File(root, "blobs-tmp").setWritable(false)
        try {
            store.put("page".toByteArray()); fail("write to a full/read-only store must throw")
        } catch (_: Exception) {
        } finally {
            File(root, "blobs").setWritable(true); File(root, "blobs-tmp").setWritable(true)
        }
        assertTrue(File(root, "blobs").walkTopDown().none { it.isFile })
    }

    @Test fun documentStatusIsReadyAfterSave() = runBlocking {
        val (id, _) = savedDoc(1)
        assertEquals(DocStatus.READY.id, c.documents.document(id)!!.status)
    }
}
