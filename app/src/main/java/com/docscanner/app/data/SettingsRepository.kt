package com.docscanner.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.docscanner.app.model.Filter
import com.docscanner.app.model.PageSize
import com.docscanner.app.model.QualityProfile
import com.docscanner.app.model.ScanMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val defaultMode: ScanMode = ScanMode.BATCH,
    val autoCapture: Boolean = true,
    val defaultFilter: Filter = Filter.AUTO,
    val exportQuality: QualityProfile = QualityProfile.BALANCED,
    val pageSize: PageSize = PageSize.FIT,
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setDefaultMode(mode: ScanMode) = edit { it[MODE] = mode.id }
    suspend fun setAutoCapture(on: Boolean) = edit { it[AUTO] = on }
    suspend fun setDefaultFilter(filter: Filter) = edit { it[FILTER] = filter.id }
    suspend fun setExportQuality(q: QualityProfile) = edit { it[QUALITY] = q.id }
    suspend fun setPageSize(size: PageSize) = edit { it[PAGE_SIZE] = size.id }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    private fun Preferences.toSettings() = Settings(
        defaultMode = ScanMode.fromId(this[MODE]),
        autoCapture = this[AUTO] ?: true,
        defaultFilter = Filter.fromId(this[FILTER]),
        exportQuality = QualityProfile.fromId(this[QUALITY]),
        pageSize = PageSize.fromId(this[PAGE_SIZE]),
    )

    private companion object {
        val MODE = stringPreferencesKey("default_mode")
        val AUTO = booleanPreferencesKey("auto_capture")
        val FILTER = stringPreferencesKey("default_filter")
        val QUALITY = stringPreferencesKey("export_quality")
        val PAGE_SIZE = stringPreferencesKey("page_size")
    }
}
