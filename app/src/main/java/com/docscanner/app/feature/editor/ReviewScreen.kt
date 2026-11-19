package com.docscanner.app.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.designsystem.components.BlobImage
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.EmptyState
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.model.DocStatus
import com.docscanner.app.model.DocumentDetail
import com.docscanner.app.model.Page
import com.docscanner.app.model.RenderState
import com.docscanner.app.model.ScanMode
import com.docscanner.app.navigation.EditFlow
import com.docscanner.app.navigation.NavActions
import com.docscanner.app.navigation.ReviewRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReviewViewModel(private val container: AppContainer, private val route: ReviewRoute) : ViewModel() {
    val doc: StateFlow<DocumentDetail?> = container.documents.observeDocument(route.docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val saving = MutableStateFlow(false)
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    fun move(pageId: String, dir: Int) = viewModelScope.launch { container.documents.movePage(pageId, dir) }
    fun delete(pageId: String) = viewModelScope.launch { container.documents.deletePage(pageId) }

    fun save(title: String) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            container.documents.saveDocument(route.docId, title)
            saving.value = false
            _saved.send(route.docId)
        }
    }
}

/** S11 Review: every page of the session, reorder, retake, re-crop, delete, name it, save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(route: ReviewRoute, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: ReviewViewModel = viewModel { ReviewViewModel(container, route) }
    val detail by vm.doc.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<Page?>(null) }
    var confirmDelete by remember { mutableStateOf<Page?>(null) }

    LaunchedEffect(detail?.summary?.title) {
        if (title == null) detail?.summary?.title?.let { title = it }
    }
    LaunchedEffect(vm) { vm.saved.collect { actions.openSavedDocument(it) } }

    val pages = detail?.pages.orEmpty()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.review_title, pages.size)) },
            navigationIcon = {
                IconButton(onClick = actions::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(onClick = { actions.openCamera(ScanMode.BATCH, targetDocId = route.docId) }) {
                    Icon(Icons.Rounded.AddAPhoto, contentDescription = stringResource(R.string.add_pages))
                }
            },
        )
        OutlinedTextField(
            value = title.orEmpty(),
            onValueChange = { title = it.take(200) },
            label = { Text(stringResource(R.string.document_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.gutterCompact),
            shape = MaterialTheme.shapes.small,
        )
        if (detail != null && pages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.PhotoCamera,
                    title = stringResource(R.string.no_pages_title),
                    body = stringResource(R.string.no_pages_body),
                ) {
                    ScannerButton(stringResource(R.string.add_pages), { actions.openCamera(ScanMode.BATCH, targetDocId = route.docId) })
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Dimens.docCardMinWidth * 3 / 4),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(Dimens.gutterCompact),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gridGap),
                verticalArrangement = Arrangement.spacedBy(Dimens.gridGap),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                    PageCell(index + 1, page) { sheetFor = page }
                }
            }
        }
        ScannerButton(
            text = stringResource(if (detail?.summary?.status == DocStatus.READY) R.string.save_changes else R.string.save),
            onClick = { vm.save(title.orEmpty()) },
            size = ButtonSize.LG,
            enabled = pages.isNotEmpty(),
            loading = saving,
            modifier = Modifier.fillMaxWidth().padding(Dimens.space4),
        )
    }

    sheetFor?.let { page ->
        val index = pages.indexOfFirst { it.id == page.id }
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Text(
                stringResource(R.string.page_n, index + 1),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.space6, vertical = Dimens.space2),
            )
            SheetAction(Icons.Rounded.Crop, stringResource(R.string.edit_page)) {
                sheetFor = null
                actions.openCrop(route.docId, page.id, EditFlow.EDIT)
            }
            SheetAction(Icons.Rounded.PhotoCamera, stringResource(R.string.retake)) {
                sheetFor = null
                actions.openCamera(ScanMode.SINGLE, targetDocId = route.docId, replacePageId = page.id)
            }
            if (index > 0) {
                SheetAction(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.move_earlier)) {
                    sheetFor = null
                    vm.move(page.id, -1)
                }
            }
            if (index in 0 until pages.lastIndex) {
                SheetAction(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.move_later)) {
                    sheetFor = null
                    vm.move(page.id, +1)
                }
            }
            SheetAction(Icons.Rounded.Delete, stringResource(R.string.delete_page), danger = true) {
                sheetFor = null
                confirmDelete = page
            }
            Box(Modifier.padding(bottom = Dimens.space6))
        }
    }

    confirmDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_page_title)) },
            text = { Text(stringResource(R.string.delete_page_body)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(page.id); confirmDelete = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PageCell(number: Int, page: Page, onClick: () -> Unit) {
    val label = stringResource(R.string.page_n, number)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = ScannerTheme.colors.surfaceSunken,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Column(Modifier.padding(Dimens.space2), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shadowElevation = Dimens.elevPage, shape = MaterialTheme.shapes.extraSmall, color = ScannerTheme.colors.page) {
                BlobImage(
                    sha = page.thumbSha,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    maxEdge = 512,
                )
            }
            Row(Modifier.padding(top = Dimens.space1), verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium)
                if (page.renderState == RenderState.PENDING || page.renderState == RenderState.RENDERING) {
                    Text(
                        " · " + stringResource(R.string.processing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (page.renderState == RenderState.FAILED) {
                    Text(
                        " · " + stringResource(R.string.render_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space2)
            .clickable(onClick = onClick),
    )
}
