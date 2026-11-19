package com.docscanner.app.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.model.Filter
import com.docscanner.app.navigation.EditFlow
import com.docscanner.app.navigation.EnhanceRoute
import com.docscanner.app.navigation.NavActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EnhanceUiState(
    val filter: Filter = Filter.AUTO,
    val rotation: Int = 0,
    val preview: Bitmap? = null,
    val rendering: Boolean = true,
    val thumbs: Map<Filter, Bitmap> = emptyMap(),
    val pageCount: Int = 1,
    val applyToAll: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

sealed interface EnhanceEvent {
    data class Saved(val docId: String) : EnhanceEvent
    data class ToReview(val docId: String) : EnhanceEvent
    data class BackToReview(val docId: String) : EnhanceEvent
}

class EnhanceViewModel(private val container: AppContainer, private val route: EnhanceRoute) : ViewModel() {
    private val _ui = MutableStateFlow(EnhanceUiState())
    val ui: StateFlow<EnhanceUiState> = _ui.asStateFlow()

    private val _events = Channel<EnhanceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val page = container.documents.page(route.pageId) ?: run { _ui.update { it.copy(failed = true) }; return@launch }
            val count = container.documents.livePages(route.docId).size
            _ui.update { it.copy(filter = page.filter, rotation = page.rotation, pageCount = count) }
            renderPreview()
            // Filter thumbnails: the real page, small.
            val thumbs = LinkedHashMap<Filter, Bitmap>()
            for (f in FILTERS) {
                container.documents.preview(route.pageId, f, page.rotation, THUMB_EDGE)?.let { thumbs[f] = it }
                _ui.update { it.copy(thumbs = LinkedHashMap(thumbs)) }
            }
        }
    }

    fun select(filter: Filter) {
        if (filter == _ui.value.filter) return
        _ui.update { it.copy(filter = filter) }
        renderPreview()
    }

    fun rotate() {
        _ui.update { it.copy(rotation = (it.rotation + 90) % 360) }
        renderPreview()
    }

    fun setApplyToAll(on: Boolean) = _ui.update { it.copy(applyToAll = on) }

    private fun renderPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _ui.update { it.copy(rendering = true) }
            val s = _ui.value
            val bmp = runCatching { container.documents.preview(route.pageId, s.filter, s.rotation, PREVIEW_EDGE) }.getOrNull()
            _ui.update { it.copy(preview = bmp ?: it.preview, rendering = false, failed = bmp == null && it.preview == null) }
        }
    }

    fun confirm() {
        val s = _ui.value
        if (s.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            container.documents.setFilterAndRotation(route.pageId, s.filter, s.rotation)
            if (s.applyToAll) container.documents.applyFilterToAll(route.docId, s.filter)
            when (route.flow) {
                EditFlow.SINGLE -> {
                    container.documents.saveDocument(route.docId, null)
                    _events.send(EnhanceEvent.Saved(route.docId))
                }
                EditFlow.REVIEW -> _events.send(EnhanceEvent.ToReview(route.docId))
                else -> _events.send(EnhanceEvent.BackToReview(route.docId))
            }
            _ui.update { it.copy(saving = false) }
        }
    }

    companion object {
        val FILTERS = listOf(Filter.ORIGINAL, Filter.AUTO, Filter.CLEAN, Filter.GRAY, Filter.BW, Filter.BW_SOFT)
        const val PREVIEW_EDGE = 1400
        const val THUMB_EDGE = 220
    }
}

/** S10 Enhance: live preview, filter carousel with real thumbnails, rotate, apply to all. */
@Composable
fun EnhanceScreen(route: EnhanceRoute, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: EnhanceViewModel = viewModel { EnhanceViewModel(container, route) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is EnhanceEvent.Saved -> actions.openSavedDocument(e.docId)
                is EnhanceEvent.ToReview -> actions.openReview(e.docId)
                is EnhanceEvent.BackToReview -> actions.backToReview(e.docId)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back_to_crop))
            }
            Text(stringResource(R.string.enhance), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::rotate) {
                Icon(Icons.Rounded.RotateRight, contentDescription = stringResource(R.string.rotate))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ScannerTheme.colors.surfaceSunken)
                .padding(Dimens.space6),
            contentAlignment = Alignment.Center,
        ) {
            val preview = ui.preview
            if (preview != null) {
                val img = remember(preview) { preview.asImageBitmap() }
                Surface(shadowElevation = Dimens.elevPage, shape = MaterialTheme.shapes.extraSmall, color = ScannerTheme.colors.page) {
                    Image(img, contentDescription = stringResource(R.string.page_preview))
                }
            } else if (ui.failed) {
                Text(stringResource(R.string.page_load_failed), textAlign = TextAlign.Center)
            }
            if (ui.rendering) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).size(Dimens.iconSm), strokeWidth = Dimens.focusRing)
        }

        HorizontalDivider(color = ScannerTheme.colors.border)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.space4, vertical = Dimens.space3),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            items(EnhanceViewModel.FILTERS) { f ->
                FilterTile(
                    label = stringResource(filterLabel(f)),
                    thumb = ui.thumbs[f],
                    selected = ui.filter == f,
                    onClick = { vm.select(f) },
                )
            }
        }
        if (ui.pageCount > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = ui.applyToAll, role = Role.Switch, onClick = { vm.setApplyToAll(!ui.applyToAll) })
                    .padding(horizontal = Dimens.space4, vertical = Dimens.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.apply_to_all, ui.pageCount), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = ui.applyToAll, onCheckedChange = null)
            }
        }
        ScannerButton(
            text = stringResource(if (route.flow == EditFlow.SINGLE) R.string.save else R.string.done),
            onClick = vm::confirm,
            size = ButtonSize.LG,
            loading = ui.saving,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.space4, vertical = Dimens.space3),
        )
    }
}

fun filterLabel(f: Filter): Int = when (f) {
    Filter.ORIGINAL -> R.string.filter_original
    Filter.AUTO -> R.string.filter_auto
    Filter.CLEAN -> R.string.filter_clean
    Filter.GRAY -> R.string.filter_gray
    Filter.BW -> R.string.filter_bw
    Filter.BW_SOFT -> R.string.filter_bw_soft
}

/** components.md "FilterTile": the real page with the filter applied, ring + label when selected. */
@Composable
private fun FilterTile(label: String, thumb: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(Dimens.filterTileW, Dimens.filterTileH)
                .clip(MaterialTheme.shapes.large)
                .background(ScannerTheme.colors.surfaceSunken)
                .border(
                    width = if (selected) Dimens.focusRing else Dimens.hairline,
                    color = if (selected) MaterialTheme.colorScheme.primary else ScannerTheme.colors.border,
                    shape = MaterialTheme.shapes.large,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                val img = remember(thumb) { thumb.asImageBitmap() }
                Image(img, contentDescription = null, modifier = Modifier.padding(Dimens.space1 + Dimens.space0_5))
            } else {
                CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = Dimens.focusRing)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.space1),
        )
    }
}
