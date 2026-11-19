package com.docscanner.app.cloud.sync

import android.util.Log
import androidx.room.withTransaction
import com.docscanner.app.cloud.CloudServices
import com.docscanner.app.data.DocumentRepository
import com.docscanner.app.data.db.AppDatabase
import com.docscanner.app.data.db.DocumentEntity
import com.docscanner.app.data.db.OutboxEntity
import com.docscanner.app.data.db.PageEntity
import com.docscanner.app.data.db.SyncShadowEntity
import com.docscanner.app.model.DocStatus
import com.docscanner.app.model.RenderState
import com.docscanner.app.storage.BlobStore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class SyncReport(val pushed: Int, val pulled: Int, val conflicts: Int, val skippedPages: Int)

class SyncPreconditionException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_CONFIGURED, NOT_SIGNED_IN, EMAIL_NOT_VERIFIED, BACKUP_OFF, LOCKED, WRONG_ACCOUNT }
}

/**
 * Two-way, end-to-end encrypted sync of documents and pages (backend.md "Sync design").
 *
 * Cloud layout (all under the user's own uid, see firestore.rules / storage.rules):
 *   users/{uid}/records/{entity}_{id}   { entity, entityId, rev, deviceId, deleted, clientUpdatedAt,
 *                                        serverUpdatedAt, payload: AES-GCM(JSON), blobs: [objectName] }
 *   users/{uid}/blobs/{objectName}      AES-GCM(original JPEG), objectName = HMAC(sha256)
 *
 * Push drains the transactional outbox; each record is written in a Firestore transaction that compares
 * the server `rev` with the last agreed one (sync_shadow) and three-way merges on mismatch.
 * Pull reads records changed since the cursor and applies them without echoing into the outbox.
 * Only originals are uploaded: processed images and thumbnails are rebuilt from original + recipe.
 */
class SyncEngine(
    private val cloud: CloudServices?,
    private val db: AppDatabase,
    private val blobs: BlobStore,
    private val vault: VaultRepository,
    private val settings: SyncSettings,
    private val documents: DocumentRepository,
) {
    private val mutex = Mutex()
    private val outbox = db.outbox()
    private val sync = db.sync()

    suspend fun syncOnce(): SyncReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cloud = cloud ?: throw SyncPreconditionException(SyncPreconditionException.Reason.NOT_CONFIGURED)
            val user = cloud.auth.currentUser ?: throw SyncPreconditionException(SyncPreconditionException.Reason.NOT_SIGNED_IN)
            val state = settings.current()
            if (!state.enabled) throw SyncPreconditionException(SyncPreconditionException.Reason.BACKUP_OFF)
            if (state.boundUid != null && state.boundUid != user.uid) {
                throw SyncPreconditionException(SyncPreconditionException.Reason.WRONG_ACCOUNT)
            }
            if (user.providerData.any { it.providerId == "password" } && !user.isEmailVerified) {
                throw SyncPreconditionException(SyncPreconditionException.Reason.EMAIL_NOT_VERIFIED)
            }
            val key = vault.localKey() ?: throw SyncPreconditionException(SyncPreconditionException.Reason.LOCKED)
            val ctx = Ctx(cloud, user.uid, key, settings.deviceId())
            val pushed = push(ctx)
            var (pulled, skipped, cursor) = pull(ctx, state.pullCursor)
            if (skipped > 0) {
                // Their documents have arrived in this pull: one more pass applies the waiting pages.
                val second = pull(ctx, cursor)
                pulled += second.first
                skipped = second.second
                cursor = second.third
            }
            settings.recordSuccess(System.currentTimeMillis(), cursor, ctx.conflicts)
            SyncReport(pushed, pulled, ctx.conflicts, skipped)
        }
    }

    /** Queues every saved document and page for upload: first backup on this phone. */
    suspend fun enqueueEverything() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            sync.readyDocumentIds().forEach { outbox.insert(OutboxEntity(entity = DOC, entityId = it, op = "upsert", entityVersion = 0, createdAt = now)) }
            sync.pageIdsOfReadyDocuments().forEach { outbox.insert(OutboxEntity(entity = PAGE, entityId = it, op = "upsert", entityVersion = 0, createdAt = now)) }
        }
    }

    /** Drops local sync state (sign-out, account deletion). Documents on the phone are kept. */
    suspend fun forgetLocalSyncState() = withContext(Dispatchers.IO) {
        sync.clearShadows()
        settings.reset()
        vault.forgetOnThisPhone()
    }

    // ================================================================== push

    private class Ctx(val cloud: CloudServices, val uid: String, val key: VaultCrypto.BackupKey, val device: String) {
        var conflicts = 0
        fun records() = cloud.firestore.collection("users").document(uid).collection("records")
        fun blobRef(name: String) = cloud.storage.reference.child("users/$uid/blobs/$name")
    }

    private suspend fun push(ctx: Ctx): Int {
        var pushed = 0
        while (true) {
            val batch = outbox.pending(BATCH)
            if (batch.isEmpty()) break
            var progressed = false
            for (group in batch) {
                try {
                    val done = pushOne(ctx, group.entity, group.entityId)
                    outbox.clear(group.entity, group.entityId, group.maxSeq)
                    if (done) pushed++
                    progressed = true
                } catch (e: Exception) {
                    outbox.markFailed(group.entity, group.entityId, e.javaClass.simpleName + ": " + (e.message ?: ""))
                    throw e // the worker retries with backoff; nothing is lost, the outbox row stays
                }
            }
            if (!progressed) break
        }
        return pushed
    }

    /** Returns false when the record was skipped (draft, nothing to send). */
    private suspend fun pushOne(ctx: Ctx, entity: String, id: String): Boolean {
        val shadow = sync.shadow(entity, id)
        val local: JsonObject = when (entity) {
            DOC -> {
                val d = db.documents().get(id)
                when {
                    d == null && shadow == null -> return false
                    d == null -> tombstone(shadow!!)
                    d.status != DocStatus.READY.id -> return false
                    else -> docPayload(d)
                }
            }
            else -> {
                val p = db.pages().get(id)
                when {
                    p == null && shadow == null -> return false
                    p == null -> tombstone(shadow!!)
                    db.documents().get(p.documentId)?.status != DocStatus.READY.id -> return false
                    else -> {
                        uploadBlob(ctx, p.originalSha)
                        pagePayload(p)
                    }
                }
            }
        }
        val recordId = "${entity}_$id"
        val ref = ctx.records().document(recordId)
        val base = shadow?.let { SyncMerge.decode(it.payload) }
        val ad = "${ctx.uid}/record/$recordId"
        var merged: JsonObject = local
        var newRev = 0L
        var txConflicts = 0 // reset on every attempt: Firestore may run the function more than once
        ctx.cloud.firestore.runTransaction { tx ->
            txConflicts = 0
            val remote = tx.get(ref)
            val remoteRev = remote.getLong("rev") ?: 0L
            merged = local
            if (remote.exists() && remoteRev != (shadow?.remoteRev ?: 0L)) {
                val remotePayload = SyncMerge.decode(VaultCrypto.decrypt(ctx.key, remote.getBlob("payload")!!.toBytes(), ad))
                val r = SyncMerge.merge(base, local, remotePayload, ctx.device, remote.getString("deviceId").orEmpty())
                merged = r.merged
                txConflicts = r.conflicts
            }
            newRev = remoteRev + 1
            tx.set(ref, recordMap(ctx, entity, id, newRev, merged))
        }.await()
        ctx.conflicts += txConflicts
        if (merged != local) applyRemote(ctx, entity, id, merged)
        sync.putShadow(SyncShadowEntity(entity, id, SyncMerge.encode(merged), newRev))
        if (entity == DOC && shadow == null) {
            // A document reaching the cloud for the first time brings its pages along.
            val now = System.currentTimeMillis()
            db.pages().livePages(id).forEach {
                outbox.insert(OutboxEntity(entity = PAGE, entityId = it.id, op = "upsert", entityVersion = 0, createdAt = now))
            }
        }
        return true
    }

    private fun recordMap(ctx: Ctx, entity: String, id: String, rev: Long, payload: JsonObject): Map<String, Any?> {
        val recordId = "${entity}_$id"
        val blobNames = payload["originalSha"]?.str()?.let { listOf(VaultCrypto.objectName(ctx.key, it)) } ?: emptyList()
        return hashMapOf(
            "entity" to entity,
            "entityId" to id,
            "rev" to rev,
            "deviceId" to ctx.device,
            "deleted" to (payload["deleted"]?.bool() ?: false),
            "clientUpdatedAt" to (payload[SyncMerge.UPDATED_AT]?.long() ?: 0L),
            "serverUpdatedAt" to FieldValue.serverTimestamp(),
            "payload" to Blob.fromBytes(VaultCrypto.encrypt(ctx.key, SyncMerge.encode(payload), "${ctx.uid}/record/$recordId")),
            "blobs" to blobNames,
        )
    }

    /** Uploads the encrypted original once. Storage retries transient failures itself; the worker retries the rest. */
    private suspend fun uploadBlob(ctx: Ctx, sha: String) {
        val ref = ctx.blobRef(VaultCrypto.objectName(ctx.key, sha))
        val exists = try {
            ref.metadata.await(); true
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw e
        }
        if (exists) return
        val bytes = VaultCrypto.encrypt(ctx.key, blobs.read(sha), "${ctx.uid}/blob/$sha")
        ref.putBytes(bytes, StorageMetadata.Builder().setContentType("application/octet-stream").build()).await()
    }

    // ================================================================== pull

    private suspend fun pull(ctx: Ctx, cursorMicros: Long): Triple<Int, Int, Long> {
        var cursor = cursorMicros
        var applied = 0
        var skippedPages = 0
        var earliestOrphan = Long.MAX_VALUE
        while (true) {
            // Two-second overlap: re-read records are skipped by the rev check.
            val since = (cursor - 2_000_000L).coerceAtLeast(0)
            val snap = ctx.records()
                .whereGreaterThan("serverUpdatedAt", micros(since))
                .orderBy("serverUpdatedAt", Query.Direction.ASCENDING)
                .limit(PULL_PAGE.toLong())
                .get(Source.SERVER).await()
            val docs = snap.documents
            if (docs.isEmpty()) break
            // Documents before pages, so a page never arrives before its document.
            val ordered = docs.sortedBy { if (it.getString("entity") == DOC) 0 else 1 }
            for (rec in ordered) {
                when (pullOne(ctx, rec)) {
                    PullResult.APPLIED -> applied++
                    PullResult.ORPHAN_PAGE -> {
                        skippedPages++
                        rec.getTimestamp("serverUpdatedAt")?.let { earliestOrphan = minOf(earliestOrphan, toMicros(it)) }
                    }
                    PullResult.SKIPPED -> Unit
                }
            }
            val last = docs.last().getTimestamp("serverUpdatedAt")?.let { toMicros(it) } ?: cursor
            if (last <= cursor || docs.size < PULL_PAGE) {
                cursor = maxOf(cursor, last)
                break
            }
            cursor = last
        }
        // A page that arrived before its document is retried next pull: keep the cursor before it.
        if (earliestOrphan != Long.MAX_VALUE) cursor = minOf(cursor, earliestOrphan - 1)
        return Triple(applied, skippedPages, cursor)
    }

    private enum class PullResult { APPLIED, SKIPPED, ORPHAN_PAGE }

    private suspend fun pullOne(ctx: Ctx, rec: DocumentSnapshot): PullResult {
        val entity = rec.getString("entity") ?: return PullResult.SKIPPED
        val id = rec.getString("entityId") ?: return PullResult.SKIPPED
        if (entity != DOC && entity != PAGE) return PullResult.SKIPPED
        val rev = rec.getLong("rev") ?: return PullResult.SKIPPED
        val shadow = sync.shadow(entity, id)
        if (shadow != null && shadow.remoteRev >= rev) return PullResult.SKIPPED
        // Local edits waiting to be pushed: the next push merges them inside its transaction.
        if (outbox.pendingFor(entity, id) > 0) return PullResult.SKIPPED
        val recordId = "${entity}_$id"
        val payload = SyncMerge.decode(
            VaultCrypto.decrypt(ctx.key, rec.getBlob("payload")!!.toBytes(), "${ctx.uid}/record/$recordId"),
        )
        if (entity == PAGE) {
            val docId = payload["documentId"]?.str() ?: return PullResult.SKIPPED
            if (db.documents().get(docId) == null) return PullResult.ORPHAN_PAGE
        }
        applyRemote(ctx, entity, id, payload)
        sync.putShadow(SyncShadowEntity(entity, id, SyncMerge.encode(payload), rev))
        return PullResult.APPLIED
    }

    /** Writes a cloud state into Room directly (no outbox echo) and fetches the original if missing. */
    private suspend fun applyRemote(ctx: Ctx, entity: String, id: String, p: JsonObject) {
        val updatedAt = p[SyncMerge.UPDATED_AT]?.long() ?: System.currentTimeMillis()
        val deletedAt = if (p["deleted"]?.bool() == true) updatedAt else null
        if (entity == DOC) {
            val existing = db.documents().get(id)
            sync.upsertDocument(
                (existing ?: DocumentEntity(
                    id = id, title = "", status = DocStatus.READY.id,
                    createdAt = p["createdAt"]?.long() ?: updatedAt, updatedAt = updatedAt,
                )).copy(
                    title = p["title"]?.str()?.ifBlank { null } ?: existing?.title ?: "Scan",
                    status = DocStatus.READY.id,
                    deletedAt = deletedAt,
                    updatedAt = updatedAt,
                ),
            )
            return
        }
        val sha = p["originalSha"]?.str() ?: return
        if (deletedAt == null && !blobs.exists(sha)) downloadBlob(ctx, sha)
        val existing = db.pages().get(id)
        val base = existing ?: PageEntity(
            id = id, documentId = p["documentId"]!!.str()!!, orderKey = p["orderKey"]!!.str()!!,
            originalPath = sha, originalSha = sha, originalWidth = p["originalWidth"]?.int() ?: 1,
            originalHeight = p["originalHeight"]?.int() ?: 1, source = p["source"]?.str() ?: "camera",
            createdAt = updatedAt, updatedAt = updatedAt,
        )
        val next = base.copy(
            documentId = p["documentId"]?.str() ?: base.documentId,
            orderKey = p["orderKey"]?.str() ?: base.orderKey,
            originalPath = sha,
            originalSha = sha,
            originalWidth = p["originalWidth"]?.int() ?: base.originalWidth,
            originalHeight = p["originalHeight"]?.int() ?: base.originalHeight,
            sourceRotation = p["sourceRotation"]?.int() ?: 0,
            quad = p["quad"]?.str(),
            quadSource = p["quadSource"]?.str() ?: "auto",
            rotation = p["rotation"]?.int() ?: 0,
            filter = p["filter"]?.str() ?: "auto",
            brightness = p["brightness"]?.int() ?: 0,
            contrast = p["contrast"]?.int() ?: 0,
            deletedAt = deletedAt,
            updatedAt = updatedAt,
            renderState = RenderState.PENDING.id,
            version = base.version + 1,
        )
        sync.upsertPage(next)
        sync.recountPages(next.documentId)
        if (deletedAt == null) documents.renderAsync(id)
    }

    private suspend fun downloadBlob(ctx: Ctx, sha: String) {
        val bytes = ctx.blobRef(VaultCrypto.objectName(ctx.key, sha)).getBytes(MAX_BLOB_BYTES).await()
        val plain = VaultCrypto.decrypt(ctx.key, bytes, "${ctx.uid}/blob/$sha")
        val stored = blobs.put(plain)
        if (stored != sha) {
            blobs.delete(stored)
            throw VaultCrypto.CorruptDataException()
        }
    }

    // ================================================================== payloads

    private fun docPayload(d: DocumentEntity) = JsonObject(
        mapOf(
            "title" to JsonPrimitive(d.title),
            "createdAt" to JsonPrimitive(d.createdAt),
            "deleted" to JsonPrimitive(d.deletedAt != null),
            SyncMerge.UPDATED_AT to JsonPrimitive(d.updatedAt),
        ),
    )

    private fun pagePayload(p: PageEntity) = JsonObject(
        mapOf(
            "documentId" to JsonPrimitive(p.documentId),
            "orderKey" to JsonPrimitive(p.orderKey),
            "originalSha" to JsonPrimitive(p.originalSha),
            "originalWidth" to JsonPrimitive(p.originalWidth),
            "originalHeight" to JsonPrimitive(p.originalHeight),
            "sourceRotation" to JsonPrimitive(p.sourceRotation),
            "source" to JsonPrimitive(p.source),
            "quad" to (p.quad?.let { JsonPrimitive(it) } ?: JsonNull),
            "quadSource" to JsonPrimitive(p.quadSource),
            "rotation" to JsonPrimitive(p.rotation),
            "filter" to JsonPrimitive(p.filter),
            "brightness" to JsonPrimitive(p.brightness),
            "contrast" to JsonPrimitive(p.contrast),
            "deleted" to JsonPrimitive(p.deletedAt != null),
            SyncMerge.UPDATED_AT to JsonPrimitive(p.updatedAt),
        ),
    )

    /** A record purged locally after 30 days in the bin: send it as deleted. */
    private fun tombstone(shadow: SyncShadowEntity): JsonObject {
        val last = SyncMerge.decode(shadow.payload)
        return JsonObject(last + mapOf("deleted" to JsonPrimitive(true), SyncMerge.UPDATED_AT to JsonPrimitive(System.currentTimeMillis())))
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull
    private fun JsonElement.long(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private fun micros(m: Long) = Timestamp(m / 1_000_000, ((m % 1_000_000) * 1000).toInt())
    private fun toMicros(t: Timestamp) = t.seconds * 1_000_000 + t.nanoseconds / 1000

    companion object {
        const val DOC = "document"
        const val PAGE = "page"
        private const val BATCH = 50
        private const val PULL_PAGE = 200
        private const val MAX_BLOB_BYTES = 60L * 1024 * 1024
        private const val TAG = "SyncEngine"

        fun log(msg: String) = Log.i(TAG, msg)
    }
}
