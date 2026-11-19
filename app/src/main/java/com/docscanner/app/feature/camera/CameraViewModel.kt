package com.docscanner.app.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.app.AppContainer
import com.docscanner.app.imaging.EdgeState
import com.docscanner.app.imaging.QuadTracker
import com.docscanner.app.model.Quad
import com.docscanner.app.model.ScanMode
import com.docscanner.app.navigation.CameraRoute
import com.docscanner.app.navigation.EditFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FlashMode { OFF, AUTO, ON, TORCH }

data class CameraUiState(
    val mode: ScanMode = ScanMode.BATCH,
    val modeLocked: Boolean = false,
    val autoCapture: Boolean = true,
    val flash: FlashMode = FlashMode.OFF,
    val docId: String? = null,
    val sessionPages: Int = 0,
    val lastThumbSha: String? = null,
    val saving: Boolean = false,
    val error: Boolean = false,
    val ready: Boolean = false,
)

sealed interface CameraEvent {
    data class OpenCrop(val docId: String, val pageId: String, val flow: String) : CameraEvent
    data class OpenReview(val docId: String) : CameraEvent
    data object Close : CameraEvent
}

/**
 * S07 camera session. Owns the edge tracker and the capture pipeline; the screen owns CameraX.
 * The draft document is created lazily on the first capture, so opening and closing the camera leaves nothing behind.
 */
class CameraViewModel(private val container: AppContainer, private val route: CameraRoute) : ViewModel() {

    private val tracker = QuadTracker()

    private val _ui = MutableStateFlow(CameraUiState(docId = route.targetDocId, modeLocked = route.replacePageId != null))
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    private val _edge = MutableStateFlow<EdgeState>(EdgeState.Searching)
    val edge: StateFlow<EdgeState> = _edge.asStateFlow()

    /** Fired when auto-capture decides the page is steady. The screen answers with a real capture. */
    private val _autoShutter = Channel<Unit>(Channel.CONFLATED)
    val autoShutter = _autoShutter.receiveAsFlow()

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            val mode = when {
                route.replacePageId != null -> ScanMode.SINGLE
                route.mode.isNotEmpty() -> ScanMode.fromId(route.mode)
                else -> s.defaultMode
            }
            _ui.update { it.copy(mode = mode, autoCapture = s.autoCapture, ready = true) }
        }
    }

    /** Called from the analysis thread for every processed frame. */
    fun onFrame(quad: Quad?, sharpness: Double) {
        val state = synchronized(tracker) { tracker.update(quad, sharpness, System.currentTimeMillis()) }
        _edge.value = state
        val ui = _ui.value
        if (state is EdgeState.Locked && ui.autoCapture && !ui.saving && ui.ready) {
            synchronized(tracker) { tracker.onCaptured() }
            _autoShutter.trySend(Unit)
        }
    }

    fun currentQuad(): Quad? = synchronized(tracker) { tracker.current }

    fun onShutterPressed() {
        synchronized(tracker) { tracker.onCaptured() }
    }

    fun setMode(mode: ScanMode) {
        if (_ui.value.modeLocked) return
        _ui.update { it.copy(mode = mode) }
    }

    fun toggleAutoCapture() {
        val next = !_ui.value.autoCapture
        _ui.update { it.copy(autoCapture = next) }
        viewModelScope.launch { container.settings.setAutoCapture(next) }
    }

    fun cycleFlash() {
        _ui.update {
            it.copy(flash = when (it.flash) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
            })
        }
    }

    fun isBusy(): Boolean = _ui.value.saving

    fun onCaptureStarted() {
        _ui.update { it.copy(saving = true, error = false) }
    }

    fun onCaptureFailed() {
        _ui.update { it.copy(saving = false, error = true) }
    }

    /** Stores the JPEG and decides where to go next. */
    fun onCaptured(jpeg: ByteArray, rotationDegrees: Int, hint: Quad?) {
        viewModelScope.launch {
            val result = runCatching {
                val settings = container.settings.current()
                val docId = _ui.value.docId ?: container.documents.createDraft()
                _ui.update { it.copy(docId = docId) }
                val pageId = container.documents.addCapturedPage(
                    docId = docId,
                    jpeg = jpeg,
                    sourceRotation = rotationDegrees,
                    hintQuad = hint,
                    defaultFilter = settings.defaultFilter,
                    replacePageId = route.replacePageId,
                )
                docId to pageId
            }
            result.onSuccess { (docId, pageId) ->
                _ui.update { it.copy(saving = false, sessionPages = it.sessionPages + 1) }
                when {
                    route.replacePageId != null -> _events.send(CameraEvent.OpenCrop(docId, pageId, EditFlow.EDIT))
                    _ui.value.mode == ScanMode.SINGLE && route.targetDocId == null && _ui.value.sessionPages == 1 ->
                        _events.send(CameraEvent.OpenCrop(docId, pageId, EditFlow.SINGLE))
                    _ui.value.mode == ScanMode.SINGLE ->
                        _events.send(CameraEvent.OpenCrop(docId, pageId, EditFlow.REVIEW))
                    else -> refreshThumb(docId)
                }
            }.onFailure {
                _ui.update { it.copy(saving = false, error = true) }
            }
        }
    }

    /** Batch: show the latest page in the stack thumbnail once its thumbnail is rendered. */
    private var thumbJob: Job? = null

    private fun refreshThumb(docId: String) {
        if (thumbJob != null) return
        thumbJob = viewModelScope.launch {
            container.documents.observeDocument(docId).collect { detail ->
                val last = detail?.pages?.lastOrNull()
                _ui.update { it.copy(lastThumbSha = last?.thumbSha ?: it.lastThumbSha) }
            }
        }
    }

    fun onDone() {
        val docId = _ui.value.docId ?: return
        viewModelScope.launch { _events.send(CameraEvent.OpenReview(docId)) }
    }

    /** True when closing would throw away pages of a brand-new draft. */
    fun needsDiscardConfirm(): Boolean = route.targetDocId == null && _ui.value.docId != null && _ui.value.sessionPages > 0

    fun discardAndClose() {
        viewModelScope.launch {
            _ui.value.docId?.let { container.documents.discardDraft(it) }
            _events.send(CameraEvent.Close)
        }
    }

    /** A session that comes back from Crop (retake) has one fewer page. */
    fun onResumedFromEditor() {
        val docId = _ui.value.docId ?: return
        viewModelScope.launch {
            val count = container.documents.livePages(docId).size
            _ui.update { it.copy(sessionPages = if (route.targetDocId == null) count else it.sessionPages.coerceAtMost(count)) }
        }
    }
}
