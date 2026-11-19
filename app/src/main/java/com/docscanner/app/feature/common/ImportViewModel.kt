package com.docscanner.app.feature.common

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.app.AppContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Result of a gallery import: where the pages went. */
data class ImportOutcome(val docId: String, val firstPageId: String, val count: Int, val intoExisting: Boolean)

/**
 * Gallery import (S08) shared by Home, Tools and Camera: Photo Picker URIs become pages in a new draft
 * (or in [targetDocId]). EXIF orientation is applied and all metadata is dropped.
 */
class ImportViewModel(private val container: AppContainer) : ViewModel() {

    private val _busy = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (done, total) while importing. */
    val busy: StateFlow<Pair<Int, Int>?> = _busy

    private val _events = Channel<Result<ImportOutcome>>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun import(uris: List<Uri>, resolver: ContentResolver, targetDocId: String?) {
        if (uris.isEmpty() || _busy.value != null) return
        viewModelScope.launch {
            _busy.value = 0 to uris.size
            val result = runCatching {
                val settings = container.settings.current()
                val docId = targetDocId ?: container.documents.createDraft()
                var first: String? = null
                uris.forEachIndexed { i, uri ->
                    val id = container.documents.importImage(docId, resolver, uri, settings.defaultFilter)
                    if (first == null) first = id
                    _busy.value = (i + 1) to uris.size
                }
                ImportOutcome(docId, first!!, uris.size, targetDocId != null)
            }
            _busy.value = null
            _events.send(result)
        }
    }
}
