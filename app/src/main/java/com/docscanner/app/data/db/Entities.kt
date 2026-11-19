package com.docscanner.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities, matching replica/schema.sql (milestone M1 subset: documents, pages, sync_outbox).
 * folders, page_ocr, tags arrive in later migrations, as planned in architecture.md section 13.
 * Times are UTC epoch milliseconds. Enums are stored as stable string ids.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["owner_id", "deleted_at", "status", "updated_at"]),
        Index(value = ["owner_id", "deleted_at", "opened_at"]),
        Index(value = ["owner_id", "title"]),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String = "local",
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    val title: String,
    val status: String,
    @ColumnInfo(name = "page_count") val pageCount: Int = 0,
    @ColumnInfo(name = "cover_page_id") val coverPageId: String? = null,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = 0,
    @ColumnInfo(name = "ocr_state") val ocrState: String = "none",
    @ColumnInfo(name = "is_locked") val isLocked: Boolean = false,
    @ColumnInfo(name = "default_filter") val defaultFilter: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "opened_at") val openedAt: Long? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val version: Int = 1,
    @ColumnInfo(name = "server_rev") val serverRev: Long? = null,
    @ColumnInfo(name = "sync_state") val syncState: String = "local",
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["render_state"]),
        Index(value = ["original_sha256"]),
    ],
)
data class PageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String = "local",
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "order_key") val orderKey: String,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "original_sha256") val originalSha: String,
    @ColumnInfo(name = "original_width") val originalWidth: Int,
    @ColumnInfo(name = "original_height") val originalHeight: Int,
    @ColumnInfo(name = "source_rotation") val sourceRotation: Int = 0,
    val source: String,
    val quad: String? = null,
    @ColumnInfo(name = "quad_source") val quadSource: String = "auto",
    val rotation: Int = 0,
    val filter: String = "auto",
    val brightness: Int = 0,
    val contrast: Int = 0,
    @ColumnInfo(name = "recipe_version") val recipeVersion: Int = 1,
    @ColumnInfo(name = "processed_path") val processedPath: String? = null,
    @ColumnInfo(name = "processed_sha256") val processedSha: String? = null,
    @ColumnInfo(name = "processed_width") val processedWidth: Int? = null,
    @ColumnInfo(name = "processed_height") val processedHeight: Int? = null,
    @ColumnInfo(name = "processed_bytes") val processedBytes: Long? = null,
    @ColumnInfo(name = "render_state") val renderState: String = "pending",
    @ColumnInfo(name = "thumb_path") val thumbPath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val version: Int = 1,
    @ColumnInfo(name = "server_rev") val serverRev: Long? = null,
    @ColumnInfo(name = "sync_state") val syncState: String = "local",
)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["entity", "entity_id"])])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "owner_id") val ownerId: String = "local",
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val op: String,
    @ColumnInfo(name = "entity_version") val entityVersion: Int,
    @ColumnInfo(name = "changed_fields") val changedFields: String? = null,
    @ColumnInfo(name = "blob_sha256") val blobSha: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

/** Row for list screens: a document plus its cover thumbnail. */
data class DocumentListRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "cover_thumb") val coverThumb: String?,
)
