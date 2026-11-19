package com.docscanner.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val COVER_THUMB =
    "(SELECT p.thumb_path FROM pages p WHERE p.document_id = d.id AND p.deleted_at IS NULL " +
        "ORDER BY p.order_key LIMIT 1)"

@Dao
interface DocumentDao {
    @Insert
    suspend fun insert(doc: DocumentEntity)

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Query(
        "SELECT d.id, d.title, d.page_count, d.status, d.created_at, d.updated_at, $COVER_THUMB AS cover_thumb " +
            "FROM documents d WHERE d.deleted_at IS NULL AND d.status = 'ready' " +
            "ORDER BY COALESCE(d.opened_at, d.updated_at) DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<DocumentListRow>>

    @Query(
        "SELECT d.id, d.title, d.page_count, d.status, d.created_at, d.updated_at, $COVER_THUMB AS cover_thumb " +
            "FROM documents d WHERE d.deleted_at IS NULL AND d.status = 'ready' ORDER BY d.updated_at DESC",
    )
    fun observeAllByDate(): Flow<List<DocumentListRow>>

    @Query(
        "SELECT d.id, d.title, d.page_count, d.status, d.created_at, d.updated_at, $COVER_THUMB AS cover_thumb " +
            "FROM documents d WHERE d.deleted_at IS NULL AND d.status = 'ready' ORDER BY d.title COLLATE NOCASE ASC",
    )
    fun observeAllByName(): Flow<List<DocumentListRow>>

    @Query(
        "SELECT d.id, d.title, d.page_count, d.status, d.created_at, d.updated_at, $COVER_THUMB AS cover_thumb " +
            "FROM documents d WHERE d.deleted_at IS NULL AND d.status = 'draft' AND d.page_count > 0 " +
            "ORDER BY d.updated_at DESC LIMIT 1",
    )
    fun observeLatestDraft(): Flow<DocumentListRow?>

    @Query("SELECT id FROM documents WHERE status = 'draft' AND page_count = 0 AND updated_at < :before")
    suspend fun emptyDraftsBefore(before: Long): List<String>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT id FROM documents WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun trashedBefore(before: Long): List<String>
}

@Dao
interface PageDao {
    @Insert
    suspend fun insert(page: PageEntity)

    @Update
    suspend fun update(page: PageEntity)

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE document_id = :docId AND deleted_at IS NULL ORDER BY order_key")
    suspend fun livePages(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE document_id = :docId AND deleted_at IS NULL ORDER BY order_key")
    fun observeLivePages(docId: String): Flow<List<PageEntity>>

    @Query("SELECT MAX(order_key) FROM pages WHERE document_id = :docId AND deleted_at IS NULL")
    suspend fun lastOrderKey(docId: String): String?

    @Query("SELECT * FROM pages WHERE render_state <> 'done' AND deleted_at IS NULL")
    suspend fun unrendered(): List<PageEntity>

    @Query(
        "SELECT original_sha256 FROM pages UNION SELECT processed_sha256 FROM pages WHERE processed_sha256 IS NOT NULL " +
            "UNION SELECT thumb_path FROM pages WHERE thumb_path IS NOT NULL",
    )
    suspend fun referencedBlobs(): List<String>
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(change: OutboxEntity)

    @Query(
        "SELECT entity, entity_id, MAX(seq) AS max_seq FROM sync_outbox WHERE entity IN ('document','page') " +
            "GROUP BY entity, entity_id ORDER BY MIN(seq) LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<OutboxGroup>

    @Query("SELECT COUNT(*) FROM (SELECT DISTINCT entity, entity_id FROM sync_outbox WHERE entity IN ('document','page'))")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE entity = :entity AND entity_id = :id")
    suspend fun pendingFor(entity: String, id: String): Int

    @Query("DELETE FROM sync_outbox WHERE entity = :entity AND entity_id = :id AND seq <= :upTo")
    suspend fun clear(entity: String, id: String, upTo: Long)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error WHERE entity = :entity AND entity_id = :id")
    suspend fun markFailed(entity: String, id: String, error: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_shadow WHERE entity = :entity AND entity_id = :id")
    suspend fun shadow(entity: String, id: String): SyncShadowEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putShadow(shadow: SyncShadowEntity)

    @Query("DELETE FROM sync_shadow")
    suspend fun clearShadows()

    /** Every saved document id (drafts are never backed up). */
    @Query("SELECT id FROM documents WHERE status = 'ready'")
    suspend fun readyDocumentIds(): List<String>

    @Query("SELECT id FROM pages WHERE document_id IN (SELECT id FROM documents WHERE status = 'ready')")
    suspend fun pageIdsOfReadyDocuments(): List<String>

    // Remote changes are written straight to the tables so they never echo back into the outbox.
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: DocumentEntity)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: PageEntity)

    @Query(
        "UPDATE documents SET page_count = (SELECT COUNT(*) FROM pages p WHERE p.document_id = documents.id " +
            "AND p.deleted_at IS NULL) WHERE id = :docId",
    )
    suspend fun recountPages(docId: String)
}
