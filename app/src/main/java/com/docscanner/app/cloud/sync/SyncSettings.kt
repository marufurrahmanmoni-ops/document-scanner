package com.docscanner.app.cloud.sync

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.docscanner.app.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class BackupState(
    /** The user's explicit, recorded consent to upload. Off by default; nothing is uploaded while false. */
    val enabled: Boolean = false,
    val consentAt: Long = 0,
    val wifiOnly: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    val conflicts: Int = 0,
    val pullCursor: Long = 0,
    val deviceId: String = "",
    val boundUid: String? = null,
)

private val Context.syncStore by preferencesDataStore(name = "sync")

class SyncSettings(private val context: Context) {

    val state: Flow<BackupState> = context.syncStore.data.map { it.toState() }

    suspend fun current(): BackupState = state.first()

    /** A random id for this install, used only to break merge ties. */
    suspend fun deviceId(): String {
        current().deviceId.takeIf { it.isNotEmpty() }?.let { return it }
        val id = UuidV7.next()
        context.syncStore.edit { it[DEVICE] = id }
        return id
    }

    suspend fun enable(uid: String, now: Long) = context.syncStore.edit {
        it[ENABLED] = true
        it[CONSENT_AT] = now
        it[UID] = uid
    }

    suspend fun disable() = context.syncStore.edit { it[ENABLED] = false }

    suspend fun setWifiOnly(on: Boolean) = context.syncStore.edit { it[WIFI] = on }

    suspend fun recordSuccess(now: Long, cursor: Long, newConflicts: Int) = context.syncStore.edit {
        it[LAST_SYNC] = now
        it[CURSOR] = cursor
        it[CONFLICTS] = (it[CONFLICTS] ?: 0) + newConflicts
        it.remove(LAST_ERROR)
    }

    suspend fun recordError(message: String) = context.syncStore.edit { it[LAST_ERROR] = message.take(200) }

    suspend fun clearConflicts() = context.syncStore.edit { it[CONFLICTS] = 0 }

    /** Restore: pull everything again from the start. */
    suspend fun resetCursor() = context.syncStore.edit { it[CURSOR] = 0L }

    /** Sign-out or account deletion: forget everything about the cloud account, keep the device id. */
    suspend fun reset() = context.syncStore.edit {
        val device = it[DEVICE]
        it.clear()
        if (device != null) it[DEVICE] = device
    }

    private fun Preferences.toState() = BackupState(
        enabled = this[ENABLED] ?: false,
        consentAt = this[CONSENT_AT] ?: 0,
        wifiOnly = this[WIFI] ?: true,
        lastSyncAt = this[LAST_SYNC] ?: 0,
        lastError = this[LAST_ERROR],
        conflicts = this[CONFLICTS] ?: 0,
        pullCursor = this[CURSOR] ?: 0,
        deviceId = this[DEVICE] ?: "",
        boundUid = this[UID],
    )

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val CONSENT_AT = longPreferencesKey("consent_at")
        val WIFI = booleanPreferencesKey("wifi_only")
        val LAST_SYNC = longPreferencesKey("last_sync")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val CONFLICTS = intPreferencesKey("conflicts")
        val CURSOR = longPreferencesKey("cursor")
        val DEVICE = stringPreferencesKey("device_id")
        val UID = stringPreferencesKey("uid")
    }
}
