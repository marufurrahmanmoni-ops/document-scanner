package com.docscanner.app.cloud.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Field-level three-way merge for synced records (backend.md "Conflict handling").
 *
 * Every synced record is a flat JSON object. The phone keeps the last version it agreed with the server
 * (the *base*, in sync_shadow). When both sides changed the same record:
 *  - a field changed on one side only takes that side's value (no data loss);
 *  - a field changed on both sides to different values is a true conflict: the newer edit wins
 *    (by [UPDATED_AT], tie broken by device id) and the conflict is counted, so the UI can say so;
 *  - an edit beats a concurrent delete: if one side deleted the record while the other changed its content,
 *    the record is kept (deleted = false) and counted as a conflict. Deleting is never silent data loss.
 */
object SyncMerge {

    const val DELETED = "deleted"
    const val UPDATED_AT = "updatedAt"

    data class Result(val merged: JsonObject, val conflicts: Int)

    fun merge(
        base: JsonObject?,
        local: JsonObject,
        remote: JsonObject,
        localDevice: String = "",
        remoteDevice: String = "",
    ): Result {
        val localTime = local.long(UPDATED_AT)
        val remoteTime = remote.long(UPDATED_AT)
        val localWins = localTime > remoteTime || (localTime == remoteTime && localDevice >= remoteDevice)
        val keys = (local.keys + remote.keys).filter { it != UPDATED_AT }
        val out = LinkedHashMap<String, JsonElement>()
        var conflicts = 0
        for (k in keys) {
            val b = base?.get(k)
            val l = local[k]
            val r = remote[k]
            out[k] = when {
                l == r -> l ?: r!!
                base != null && l == b -> r ?: l!!
                base != null && r == b -> l ?: r!!
                else -> {
                    conflicts++
                    if (localWins) (l ?: r!!) else (r ?: l!!)
                }
            }
        }
        // Edit beats concurrent delete.
        val deleted = out[DELETED]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
        if (deleted) {
            val deleter = if (local.bool(DELETED)) local else remote
            val other = if (deleter === local) remote else local
            val otherChangedContent = keys.any { k -> k != DELETED && base != null && other[k] != base[k] }
            val otherDeleted = other.bool(DELETED)
            if (otherChangedContent && !otherDeleted) {
                out[DELETED] = JsonPrimitive(false)
                conflicts++
            }
        }
        out[UPDATED_AT] = JsonPrimitive(maxOf(localTime, remoteTime))
        return Result(JsonObject(out), conflicts)
    }

    private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(obj: JsonObject): ByteArray = json.encodeToString(JsonObject.serializer(), obj).toByteArray()
    fun decode(bytes: ByteArray): JsonObject = json.decodeFromString(JsonObject.serializer(), String(bytes))
}
