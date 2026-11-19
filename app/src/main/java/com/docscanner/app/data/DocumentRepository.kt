package com.docscanner.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.docscanner.app.data.db.AppDatabase
import com.docscanner.app.data.db.DocumentEntity
import com.docscanner.app.data.db.DocumentListRow
import com.docscanner.app.data.db.OutboxEntity
import com.docscanner.app.data.db.PageEntity
import com.docscanner.app.imaging.EdgeDetector
import com.docscanner.app.imaging.ImageOps
import com.docscanner.app.model.DocStatus
import com.docscanner.app.model.DocumentDetail
import com.docscanner.app.model.DocumentSummary
import com.docscanner.app.model.Filter
import com.docscanner.app.model.Page
import com.docscanner.app.model.QualityProfile
import com.docscanner.app.model.Quad
import com.docscanner.app.model.QuadSource
import com.docscanner.app.model.RenderState
import com.docscanner.app.storage.BlobStore
import com.docscanner.app.util.OrderKey
import com.docscanner.app.util.Titles
import com.docscanner.app.util.UuidV7
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The single writer for documents and pages (architecture.md principle 4).
 * Every write: blobs first (atomic), then one DB transaction that also appends to sync_outbox.
 */
class DocumentRepository(
    private val db: AppDatabase,
    private val blobs: BlobStore,
    private val renderer: PageRenderer,
    private val appScope: CoroutineScope,
    lowRamDevice: Boolean,
) {
    private val docs = db.documents()
    private val pages = db.pages()
    private val outbox = db.outbox()

    /** Limits concurrent full-resolution work: 1 on low-RAM devices, 2 otherwise (architecture.md 4.4). */
    private val imaging = Semaphore(if (lowRamDevice) 1 else 2)

    // ---------------------------------------------------------------- reads

    fun observeRecent(limit: Int): Flow<List<DocumentSummary>> = docs.observeRecent(limit).map { rows -> rows.map { it.toSummary() } }

    fun observeAll(byName: Boolean): Flow<List<DocumentSummary>> =
        (if (byName) docs.observeAllByName() else docs.observeAllByDate()).map { rows -> rows.map { it.toSummary() } }

    fun observeLatestDraft(): Flow<DocumentSummary?> = docs.observeLatestDraft().map { it?.toSummary() }

    fun observeDocument(docId: String): Flow<DocumentDetail?> =
        combine(docs.observe(docId), pages.observeLivePages(docId)) { doc, pageRows ->
            if (doc == null || doc.deletedAt != null) null
            else DocumentDetail(
                summary = DocumentSummary(
                    id = doc.id,
                    title = doc.title,
                    pageCount = pageRows.size,
                    coverThumbSha = pageRows.firstOrNull()?.thumbPath,
                    updatedAt = doc.updatedAt,
                    createdAt = doc.createdAt,
                    status = if (doc.status == DocStatus.READY.id) DocStatus.READY else DocStatus.DRAFT,
                ),
                pages = pageRows.map { it.toModel() },
            )
        }

    suspend fun page(pageId: String): Page? = pages.get(pageId)?.toModel()

    suspend fun livePages(docId: String): List<Page> = pages.livePages(docId).map { it.toModel() }

    suspend fun document(docId: String): DocumentEntity? = docs.get(docId)

    // ---------------------------------------------------------------- scan session

    /** Creates an empty draft document. Its title is fixed now, in the device time zone. */
    suspend fun createDraft(): String {
        val now = System.currentTimeMillis()
        val id = UuidV7.next(now)
        db.withTransaction {
            docs.insert(
                DocumentEntity(
                    id = id,
                    title = Titles.defaultTitle(now),
                    status = DocStatus.DRAFT.id,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            log("document", id, "upsert", 1, now)
        }
        return id
    }

    /**
     * Stores a captured JPEG as an immutable original and appends (or replaces) a page.
     * [hintQuad] comes from the live analysis frame. It is refined by running detection on the capture itself.
     */
    suspend fun addCapturedPage(
        docId: String,
        jpeg: ByteArray,
        sourceRotation: Int,
        hintQuad: Quad?,
        defaultFilter: Filter,
        replacePageId: String? = null,
    ): String = withContext(Dispatchers.Default) {
        val sha = blobs.put(jpeg)
        val bounds = ImageOps.bounds(jpeg)
        val refined = imaging.withPermit { detectOnImage(jpeg, sourceRotation) }
        val quad = refined ?: hintQuad
        val source = if (quad == null) QuadSource.FULL else QuadSource.AUTO
        insertOrReplacePage(docId, sha, bounds, sourceRotation, quad, source, defaultFilter, "camera", replacePageId)
    }

    /** Imports a gallery image: applies its EXIF orientation, drops all metadata (GPS included), re-encodes. */
    suspend fun importImage(docId: String, resolver: ContentResolver, uri: Uri, defaultFilter: Filter): String =
        withContext(Dispatchers.IO) {
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("could not read image")
            val orientation = runCatching {
                ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            val clean = imaging.withPermit {
                val decoded = ImageOps.decodeSampled(raw, QualityProfile.ORIGINAL.longEdge)
                val upright = ImageOps.rotateBitmap(decoded, degrees)
                if (upright !== decoded) decoded.recycle()
                val bytes = ImageOps.encodeJpeg(upright, 95)
                upright.recycle()
                bytes
            }
            val sha = blobs.put(clean)
            val bounds = ImageOps.bounds(clean)
            val quad = imaging.withPermit { detectOnImage(clean, 0) }
            val source = if (quad == null) QuadSource.FULL else QuadSource.AUTO
            insertOrReplacePage(docId, sha, bounds, 0, quad, source, defaultFilter, "gallery", null)
        }

    private suspend fun insertOrReplacePage(
        docId: String,
        sha: String,
        bounds: ImageOps.Bounds,
        sourceRotation: Int,
        quad: Quad?,
        source: QuadSource,
        filter: Filter,
        origin: String,
        replacePageId: String?,
    ): String {
        val now = System.currentTimeMillis()
        val pageId: String = db.withTransaction {
            val existing = replacePageId?.let { pages.get(it) }
            val id: String
            if (existing != null) {
                id = existing.id
                val updated = existing.copy(
                    originalPath = sha,
                    originalSha = sha,
                    originalWidth = bounds.width,
                    originalHeight = bounds.height,
                    sourceRotation = sourceRotation,
                    source = origin,
                    quad = quad?.serialize(),
                    quadSource = source.id,
                    rotation = 0,
                    renderState = RenderState.PENDING.id,
                    updatedAt = now,
                    version = existing.version + 1,
                )
                pages.update(updated)
                log("page", id, "upsert", updated.version, now)
            } else {
                id = UuidV7.next(now)
                val key = OrderKey.after(pages.lastOrderKey(docId))
                pages.insert(
                    PageEntity(
                        id = id,
                        documentId = docId,
                        orderKey = key,
                        originalPath = sha,
                        originalSha = sha,
                        originalWidth = bounds.width,
                        originalHeight = bounds.height,
                        sourceRotation = sourceRotation,
                        source = origin,
                        quad = quad?.serialize(),
                        quadSource = source.id,
                        filter = filter.id,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                log("page", id, "upsert", 1, now)
            }
            log("page", id, "blob", 1, now, blobSha = sha)
            touchDocument(docId, now)
            id
        }
        renderAsync(pageId)
        return pageId
    }

    private suspend fun detectOnImage(jpeg: ByteArray, sourceRotation: Int): Quad? {
        val small = ImageOps.decodeSampled(jpeg, 960)
        val upright = ImageOps.rotateBitmap(small, sourceRotation)
        if (upright !== small) small.recycle()
        val gray = ImageOps.toGrayMat(upright)
        upright.recycle()
        return try { EdgeDetector.detect(gray).quad } finally { gray.release() }
    }

    /** Runs edge detection again on a page (the crop screen's Auto button). */
    suspend fun redetect(pageId: String): Quad? = withContext(Dispatchers.Default) {
        val p = pages.get(pageId) ?: return@withContext null
        imaging.withPermit { detectOnImage(blobs.read(p.originalSha), p.sourceRotation) }
    }

    // ---------------------------------------------------------------- edits (the recipe)

    suspend fun setQuad(pageId: String, quad: Quad?, source: QuadSource) {
        editPage(pageId) { it.copy(quad = quad?.serialize(), quadSource = source.id) }
    }

    suspend fun setFilterAndRotation(pageId: String, filter: Filter, rotation: Int) {
        editPage(pageId) { it.copy(filter = filter.id, rotation = ((rotation % 360) + 360) % 360) }
    }

    suspend fun applyFilterToAll(docId: String, filter: Filter) {
        pages.livePages(docId).filter { it.filter != filter.id }.forEach { p ->
            editPage(p.id) { it.copy(filter = filter.id) }
        }
    }

    private suspend fun editPage(pageId: String, change: (PageEntity) -> PageEntity) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val current = pages.get(pageId) ?: return@withTransaction
            val next = change(current).copy(
                renderState = RenderState.PENDING.id,
                updatedAt = now,
                version = current.version + 1,
            )
            pages.update(next)
            log("page", pageId, "upsert", next.version, now)
            touchDocument(current.documentId, now)
        }
        renderAsync(pageId)
    }

    /** Moves a page one step earlier (-1) or later (+1). One row changes: a new fractional key. */
    suspend fun movePage(pageId: String, direction: Int) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val page = pages.get(pageId) ?: return@withTransaction
            val list = pages.livePages(page.documentId)
            val idx = list.indexOfFirst { it.id == pageId }
            val target = idx + direction
            if (idx < 0 || target !in list.indices) return@withTransaction
            val newKey = if (direction < 0) {
                OrderKey.between(list.getOrNull(target - 1)?.orderKey, list[target].orderKey)
            } else {
                OrderKey.between(list[target].orderKey, list.getOrNull(target + 1)?.orderKey)
            }
            val next = page.copy(orderKey = newKey, updatedAt = now, version = page.version + 1)
            pages.update(next)
            log("page", pageId, "upsert", next.version, now)
            touchDocument(page.documentId, now)
        }
    }

    suspend fun deletePage(pageId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val page = pages.get(pageId) ?: return@withTransaction
            val next = page.copy(deletedAt = now, updatedAt = now, version = page.version + 1)
            pages.update(next)
            log("page", pageId, "delete", next.version, now)
            touchDocument(page.documentId, now)
        }
    }

    // ---------------------------------------------------------------- document lifecycle

    /** Draft -> ready. Renders anything still pending first so the document is complete when it opens. */
    suspend fun saveDocument(docId: String, title: String?) {
        pages.livePages(docId).filter { it.renderState != RenderState.DONE.id }.forEach { renderNow(it.id) }
        val now = System.currentTimeMillis()
        db.withTransaction {
            val doc = docs.get(docId) ?: return@withTransaction
            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: doc.title
            val next = doc.copy(
                status = DocStatus.READY.id,
                title = cleanTitle,
                updatedAt = now,
                openedAt = now,
                version = doc.version + 1,
            )
            docs.update(next)
            log("document", docId, "upsert", next.version, now)
        }
    }

    suspend fun rename(docId: String, title: String) {
        val clean = title.trim().take(200)
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            val doc = docs.get(docId) ?: return@withTransaction
            val next = doc.copy(title = clean, updatedAt = now, version = doc.version + 1)
            docs.update(next)
            log("document", docId, "upsert", next.version, now)
        }
    }

    suspend fun markOpened(docId: String) {
        val doc = docs.get(docId) ?: return
        docs.update(doc.copy(openedAt = System.currentTimeMillis()))
    }

    /** Soft delete (tombstone). Purged after 30 days by [housekeeping]. */
    suspend fun trashDocument(docId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val doc = docs.get(docId) ?: return@withTransaction
            val next = doc.copy(deletedAt = now, updatedAt = now, version = doc.version + 1)
            docs.update(next)
            log("document", docId, "delete", next.version, now)
        }
    }

    suspend fun restoreDocument(docId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val doc = docs.get(docId) ?: return@withTransaction
            val next = doc.copy(deletedAt = null, updatedAt = now, version = doc.version + 1)
            docs.update(next)
            log("document", docId, "upsert", next.version, now)
        }
    }

    /** Throws away an unsaved scan. Blobs are collected by [housekeeping]. */
    suspend fun discardDraft(docId: String) {
        val doc = docs.get(docId) ?: return
        if (doc.status == DocStatus.DRAFT.id) docs.hardDelete(docId)
    }

    // ---------------------------------------------------------------- rendering

    fun renderAsync(pageId: String) {
        appScope.launch(Dispatchers.Default) { runCatching { renderNow(pageId) } }
    }

    /**
     * Renders processed + thumbnail at the Balanced profile. Writes only if the recipe did not change meanwhile
     * (version check), otherwise the newer edit's own render wins.
     */
    suspend fun renderNow(pageId: String) = withContext(Dispatchers.Default) {
        val start = pages.get(pageId) ?: return@withContext
        if (start.deletedAt != null) return@withContext
        val profile = QualityProfile.BALANCED
        val result = runCatching {
            imaging.withPermit { renderer.render(start.toModel(), profile.longEdge, profile.jpegQuality, start.brightness, start.contrast) }
        }
        val now = System.currentTimeMillis()
        val rendered = result.getOrNull()
        if (rendered == null) {
            db.withTransaction {
                val cur = pages.get(pageId) ?: return@withTransaction
                if (cur.version == start.version) pages.update(cur.copy(renderState = RenderState.FAILED.id))
            }
            return@withContext
        }
        val processedSha = blobs.put(rendered.jpeg)
        val thumbSha = blobs.put(rendered.thumbJpeg)
        db.withTransaction {
            val cur = pages.get(pageId) ?: return@withTransaction
            if (cur.version != start.version) return@withTransaction
            pages.update(
                cur.copy(
                    processedPath = processedSha,
                    processedSha = processedSha,
                    processedWidth = rendered.width,
                    processedHeight = rendered.height,
                    processedBytes = rendered.jpeg.size.toLong(),
                    thumbPath = thumbSha,
                    renderState = RenderState.DONE.id,
                    updatedAt = now,
                ),
            )
            val doc = docs.get(cur.documentId)
            if (doc != null) {
                val total = pages.livePages(cur.documentId).sumOf { it.processedBytes ?: 0L }
                docs.update(doc.copy(totalBytes = total))
            }
        }
    }

    /** Preview for the enhance screen (no DB writes). */
    suspend fun preview(pageId: String, filter: Filter, rotation: Int, longEdge: Int): Bitmap? =
        withContext(Dispatchers.Default) {
            val p = pages.get(pageId)?.toModel() ?: return@withContext null
            imaging.withPermit { renderer.preview(p, filter, rotation, longEdge) }
        }

    suspend fun uprightOriginal(pageId: String): Bitmap? = withContext(Dispatchers.Default) {
        val p = pages.get(pageId)?.toModel() ?: return@withContext null
        imaging.withPermit { renderer.uprightOriginal(p) }
    }

    /** Full render at an export profile, without touching the DB. */
    suspend fun renderForExport(page: Page, profile: QualityProfile): ByteArray = withContext(Dispatchers.Default) {
        if (profile == QualityProfile.BALANCED && page.processedSha != null && page.renderState == RenderState.DONE) {
            return@withContext blobs.read(page.processedSha)
        }
        imaging.withPermit { renderer.render(page, profile.longEdge, profile.jpegQuality).jpeg }
    }

    // ---------------------------------------------------------------- housekeeping

    /**
     * On start: resume renders interrupted by process death, purge trash older than 30 days,
     * drop empty drafts, and delete blobs no row references (older than 1 hour).
     */
    suspend fun housekeeping() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        pages.unrendered().forEach { renderAsync(it.id) }
        docs.trashedBefore(now - THIRTY_DAYS).forEach { docs.hardDelete(it) }
        docs.emptyDraftsBefore(now - ONE_DAY).forEach { docs.hardDelete(it) }
        val referenced = pages.referencedBlobs().toHashSet()
        blobs.allHashes().forEach { (sha, modified) ->
            if (sha !in referenced && now - modified > ONE_HOUR) blobs.delete(sha)
        }
        blobs.clearTmp()
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun touchDocument(docId: String, now: Long) {
        val doc = docs.get(docId) ?: return
        val count = pages.livePages(docId).size
        docs.update(doc.copy(pageCount = count, updatedAt = now, version = doc.version + 1))
    }

    private suspend fun log(entity: String, id: String, op: String, version: Int, now: Long, blobSha: String? = null) {
        outbox.insert(
            OutboxEntity(entity = entity, entityId = id, op = op, entityVersion = version, blobSha = blobSha, createdAt = now),
        )
    }

    private fun DocumentListRow.toSummary() = DocumentSummary(
        id = id,
        title = title,
        pageCount = pageCount,
        coverThumbSha = coverThumb,
        updatedAt = updatedAt,
        createdAt = createdAt,
        status = if (status == DocStatus.READY.id) DocStatus.READY else DocStatus.DRAFT,
    )

    private fun PageEntity.toModel() = Page(
        id = id,
        documentId = documentId,
        orderKey = orderKey,
        originalSha = originalSha,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        sourceRotation = sourceRotation,
        quad = Quad.parse(quad),
        quadSource = QuadSource.fromId(quadSource),
        rotation = rotation,
        filter = Filter.fromId(filter),
        processedSha = processedSha,
        thumbSha = thumbPath,
        renderState = RenderState.entries.firstOrNull { it.id == renderState } ?: RenderState.PENDING,
        version = version,
    )

    private companion object {
        const val ONE_HOUR = 60L * 60 * 1000
        const val ONE_DAY = 24 * ONE_HOUR
        const val THIRTY_DAYS = 30 * ONE_DAY
    }
}

