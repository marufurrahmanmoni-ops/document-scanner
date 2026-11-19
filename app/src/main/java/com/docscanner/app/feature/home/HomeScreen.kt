package com.docscanner.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.ButtonVariant
import com.docscanner.app.designsystem.components.DocumentTile
import com.docscanner.app.designsystem.components.EmptyState
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.components.SectionHeader
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.feature.common.docA11y
import com.docscanner.app.feature.common.docMeta
import com.docscanner.app.feature.common.pagesLabel
import com.docscanner.app.feature.common.rememberGalleryImport
import com.docscanner.app.feature.common.rememberImportViewModel
import com.docscanner.app.model.DocumentSummary
import com.docscanner.app.model.ScanMode
import com.docscanner.app.navigation.NavActions
import com.docscanner.app.navigation.Tab
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    /** null = still loading (skeleton), empty list = empty state. */
    val recent: StateFlow<List<DocumentSummary>?> =
        container.documents.observeRecent(6).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val draft: StateFlow<DocumentSummary?> =
        container.documents.observeLatestDraft().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun discardDraft(id: String) {
        viewModelScope.launch { container.documents.discardDraft(id) }
    }
}

/** S03 Home: greeting, quick actions, unsaved-scan banner, recent documents. */
@Composable
fun HomeScreen(contentPadding: PaddingValues, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val importVm = rememberImportViewModel("home")
    val importing by importVm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importFailed = stringResource(R.string.import_failed)
    val openGallery = rememberGalleryImport(importVm, targetDocId = null) { result ->
        result.onSuccess { actions.routeImport(it) }
            .onFailure { scope.launch { snackbar.showSnackbar(importFailed) } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Dimens.docCardMinWidth),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            contentPadding = PaddingValues(
                start = Dimens.gutterCompact,
                end = Dimens.gutterCompact,
                bottom = contentPadding.calculateBottomPadding() + Dimens.space12,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gridGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.gridGap),
        ) {
            full {
                Text(
                    greeting(),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .padding(top = Dimens.space6, bottom = Dimens.space2)
                        .semantics { heading() },
                )
            }
            importing?.let { (done, total) ->
                full {
                    Column(Modifier.padding(vertical = Dimens.space2)) {
                        Text(stringResource(R.string.importing_n_of_m, done, total), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done / total.toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2),
                        )
                    }
                }
            }
            draft?.let { d ->
                full { DraftBanner(d, onContinue = { actions.openReview(d.id) }, onDiscard = { vm.discardDraft(d.id) }) }
            }
            full { SectionHeader(stringResource(R.string.quick_actions)) }
            full {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    QuickAction(Icons.Rounded.DocumentScanner, stringResource(R.string.qa_scan), Modifier.weight(1f)) {
                        actions.openCamera(ScanMode.SINGLE)
                    }
                    QuickAction(Icons.Rounded.FilterNone, stringResource(R.string.qa_batch), Modifier.weight(1f)) {
                        actions.openCamera(ScanMode.BATCH)
                    }
                    QuickAction(Icons.Rounded.PhotoLibrary, stringResource(R.string.qa_import), Modifier.weight(1f)) {
                        openGallery()
                    }
                }
            }
            full {
                Row(
                    Modifier.fillMaxWidth().padding(top = Dimens.space6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.recent),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (!recent.isNullOrEmpty()) {
                        ScannerButton(
                            text = stringResource(R.string.see_all),
                            onClick = { actions.openTab(Tab.FILES) },
                            variant = ButtonVariant.TEXT,
                            size = ButtonSize.SM,
                        )
                    }
                }
            }
            val docs = recent
            when {
                docs == null -> items(2) { TileSkeleton() }
                docs.isEmpty() -> full {
                    EmptyState(
                        icon = Icons.Rounded.Description,
                        title = stringResource(R.string.empty_home_title),
                        body = stringResource(R.string.empty_home_body),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ScannerButton(
                            text = stringResource(R.string.scan_first),
                            onClick = { actions.openCamera(null) },
                            icon = Icons.Rounded.DocumentScanner,
                        )
                    }
                }
                else -> items(docs, key = { it.id }) { doc ->
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
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + Dimens.space16),
        )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun greeting(): String {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    return stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        },
    )
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(Dimens.quickActionH),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(Dimens.space8)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconSm))
            }
            Spacer(Modifier.height(Dimens.space1 + Dimens.space0_5))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DraftBanner(draft: DocumentSummary, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2),
    ) {
        Column(Modifier.padding(Dimens.space4)) {
            Text(
                stringResource(R.string.draft_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.draft_body, pagesLabel(draft.pageCount)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = Dimens.space2),
                horizontalArrangement = Arrangement.End,
            ) {
                ScannerButton(stringResource(R.string.discard), onDiscard, variant = ButtonVariant.TEXT, size = ButtonSize.SM)
                ScannerButton(stringResource(R.string.continue_scan), onContinue, variant = ButtonVariant.PRIMARY, size = ButtonSize.SM)
            }
        }
    }
}

@Composable
fun TileSkeleton() {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.docCardMinWidth * 4 / 3)
                .background(ScannerTheme.colors.surfaceSunken, MaterialTheme.shapes.medium),
        )
        Box(
            Modifier
                .padding(top = Dimens.space2)
                .widthIn(max = Dimens.docCardMinWidth * 3 / 4)
                .fillMaxWidth()
                .height(Dimens.space4)
                .background(ScannerTheme.colors.surfaceSunken, MaterialTheme.shapes.extraSmall),
        )
    }
}

