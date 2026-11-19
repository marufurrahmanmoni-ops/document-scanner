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
}
