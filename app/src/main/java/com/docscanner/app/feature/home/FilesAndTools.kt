package com.docscanner.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.designsystem.components.DocumentTile
import com.docscanner.app.designsystem.components.EmptyState
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.components.SectionHeader
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.feature.common.docA11y
import com.docscanner.app.feature.common.docMeta
import com.docscanner.app.feature.common.rememberGalleryImport
import com.docscanner.app.feature.common.rememberImportViewModel
import com.docscanner.app.model.DocumentSummary
import com.docscanner.app.model.ScanMode
import com.docscanner.app.navigation.NavActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilesViewModel(container: AppContainer) : ViewModel() {
    val byName = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val docs: StateFlow<List<DocumentSummary>?> = byName
        .flatMapLatest { container.documents.observeAll(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** S04 Files: every saved document, sortable by date or name. Folders arrive in M3 (build-log.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(contentPadding: PaddingValues, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: FilesViewModel = viewModel { FilesViewModel(container) }
    val docs by vm.docs.collectAsStateWithLifecycle()
    val byName by vm.byName.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.gutterCompact, vertical = Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tab_files),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            SingleChoiceSegmentedButtonRow {
                val options = listOf(false to R.string.sort_date, true to R.string.sort_name)
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = byName == value,
                        onClick = { vm.byName.value = value },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        val list = docs
        when {
            list == null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(Dimens.docCardMinWidth),
                contentPadding = PaddingValues(horizontal = Dimens.gutterCompact),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gridGap),
            ) { items(4) { TileSkeleton() } }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.empty_files_title),
                    body = stringResource(R.string.empty_files_body),
                ) {
                    ScannerButton(stringResource(R.string.scan_first), { actions.openCamera(null) }, icon = Icons.Rounded.DocumentScanner)
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(Dimens.docCardMinWidth),
                contentPadding = PaddingValues(
                    start = Dimens.gutterCompact,
                    end = Dimens.gutterCompact,
                    bottom = contentPadding.calculateBottomPadding() + Dimens.space12,
                ),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gridGap),
                verticalArrangement = Arrangement.spacedBy(Dimens.gridGap),
            ) {
                items(list, key = { it.id }) { doc ->
                    DocumentTile(
                        title = doc.title,
                        meta = docMeta(doc),
                        pageCount = doc.pageCount,
                        thumbSha = doc.coverThumbSha,
                        onClick = { actions.openDocument(doc.id) },
                        a11yLabel = docA11y(doc),
                    )
                }
            }
        }
    }
}

/** S05 Tools: only tools that work today. More arrive with their features (no placeholders). */
@Composable
fun ToolsScreen(contentPadding: PaddingValues, actions: NavActions) {
    val importVm = rememberImportViewModel("tools")
    val importing by importVm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.import_failed)
    val openGallery = rememberGalleryImport(importVm, null) { r ->
        r.onSuccess { actions.routeImport(it) }.onFailure { scope.launch { snackbar.showSnackbar(failed) } }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutterCompact)
                .padding(bottom = contentPadding.calculateBottomPadding() + Dimens.space12),
        ) {
            Text(
                stringResource(R.string.tab_tools),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = Dimens.space4).semantics { heading() },
            )
            importing?.let { (done, total) ->
                Text(stringResource(R.string.importing_n_of_m, done, total), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done / total.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.space2),
                )
            }
            SectionHeader(stringResource(R.string.tools_scan))
            ToolRow(Icons.Rounded.DocumentScanner, stringResource(R.string.tool_single), stringResource(R.string.tool_single_desc)) {
                actions.openCamera(ScanMode.SINGLE)
            }
            ToolRow(Icons.Rounded.FilterNone, stringResource(R.string.tool_batch), stringResource(R.string.tool_batch_desc)) {
                actions.openCamera(ScanMode.BATCH)
            }
            ToolRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.tool_import), stringResource(R.string.tool_import_desc)) {
                openGallery()
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.space16 + Dimens.space6))
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.space1),
    ) {
        Row(Modifier.padding(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(Dimens.space10),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.padding(start = Dimens.space4).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

