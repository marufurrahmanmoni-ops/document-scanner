package com.docscanner.app.feature.document

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.data.ExportFormat
import com.docscanner.app.data.ExportRequest
import com.docscanner.app.data.ExportResult
import com.docscanner.app.data.Settings
import com.docscanner.app.designsystem.components.BlobImage
import com.docscanner.app.designsystem.components.EmptyState
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.model.DocumentDetail
import com.docscanner.app.model.ScanMode
import com.docscanner.app.navigation.DocumentRoute
import com.docscanner.app.navigation.NavActions
import com.docscanner.app.util.Titles
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Export progress: null = idle. */
data class ExportProgress(val done: Int, val total: Int)

sealed interface DocEvent {
    data class Share(val result: ExportResult) : DocEvent
    data object SavedToDevice : DocEvent
    data object ExportFailed : DocEvent
    data object Trashed : DocEvent
}

class DocumentViewModel(private val container: AppContainer, private val docId: String) : ViewModel() {
    val doc: StateFlow<DocumentDetail?> = container.documents.observeDocument(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val progress = MutableStateFlow<ExportProgress?>(null)

    private val _events = Channel<DocEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Remembered between the picker and the export so "Save to device" can finish after the picker returns. */
    private var pendingRequest: ExportRequest? = null

    init {
        viewModelScope.launch { container.documents.markOpened(docId) }
    }

    fun rename(title: String) = viewModelScope.launch { container.documents.rename(docId, title) }

    fun trash() = viewModelScope.launch {
        container.documents.trashDocument(docId)
        _events.send(DocEvent.Trashed)
    }

    fun share(request: ExportRequest) {
        if (progress.value != null) return
        viewModelScope.launch {
            val r = runExport(request) ?: return@launch
            _events.send(DocEvent.Share(r))
        }
    }

    fun rememberForSave(request: ExportRequest) {
        pendingRequest = request
    }

    fun saveTo(target: Uri?, resolver: android.content.ContentResolver) {
        val request = pendingRequest ?: return
        pendingRequest = null
        if (target == null || progress.value != null) return
        viewModelScope.launch {
            val r = runExport(request) ?: return@launch
            val ok = runCatching {
                if (request.format == ExportFormat.PDF) container.exports.copyTo(r.files.first(), target, resolver)
                else container.exports.copyToTree(r.files, target, resolver, r.mimeType)
            }.isSuccess
            _events.send(if (ok) DocEvent.SavedToDevice else DocEvent.ExportFailed)
            container.exports.cleanup(maxAgeMs = 0)
        }
    }

    private suspend fun runExport(request: ExportRequest): ExportResult? {
        progress.value = ExportProgress(0, doc.value?.pages?.size ?: 0)
        val result = runCatching {
            container.exports.export(request) { done, total -> progress.value = ExportProgress(done, total) }
        }
        progress.value = null
        if (result.isFailure) _events.send(DocEvent.ExportFailed)
        return result.getOrNull()
    }

    fun rememberDefaults(request: ExportRequest) = viewModelScope.launch {
        container.settings.setExportQuality(request.profile)
        container.settings.setPageSize(request.pageSize)
    }
}

/** S12 Document view (pages as paper on a desk) + S16 share/export sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(route: DocumentRoute, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: DocumentViewModel = viewModel { DocumentViewModel(container, route.docId) }
    val detail by vm.doc.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }

    val savedMsg = stringResource(R.string.saved_to_device)
    val failedMsg = stringResource(R.string.export_failed)
    val shareTitle = stringResource(R.string.share_via)

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        vm.saveTo(uri, context.contentResolver)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.saveTo(uri, context.contentResolver)
    }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is DocEvent.Share -> {
                    val uris = ArrayList(e.result.files.map { container.exports.uriFor(it) })
                    val base = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                    val intent = base.apply {
                        type = e.result.mimeType
                        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
                            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                }
                DocEvent.SavedToDevice -> scope.launch { snackbar.showSnackbar(savedMsg) }
                DocEvent.ExportFailed -> scope.launch { snackbar.showSnackbar(failedMsg) }
                DocEvent.Trashed -> actions.back()
            }
        }
    }

    val doc = detail
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        doc?.summary?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = Dimens.space2),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmTrash = true },
                        )
                    }
                },
            )
            HorizontalDivider(color = ScannerTheme.colors.border)

            Box(Modifier.weight(1f).fillMaxWidth().background(ScannerTheme.colors.surfaceSunken)) {
                when {
                    doc == null -> Unit
                    doc.pages.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.AddAPhoto,
                        title = stringResource(R.string.no_pages_title),
                        body = stringResource(R.string.no_pages_body),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        ScannerButton(stringResource(R.string.add_pages), { actions.openCamera(ScanMode.BATCH, targetDocId = route.docId) })
                    }
                    else -> PageList(doc)
                }
            }

            progress?.let { p ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.exporting_n_of_m, p.done, p.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(Dimens.space3),
                    )
                }
            }

            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = Dimens.space0) {
                NavigationBarItem(
                    selected = false,
                    enabled = !doc?.pages.isNullOrEmpty() && progress == null,
                    onClick = { exportSheet = true },
                    icon = { Icon(Icons.Rounded.Share, null) },
                    label = { Text(stringResource(R.string.share)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { actions.openCamera(ScanMode.BATCH, targetDocId = route.docId) },
                    icon = { Icon(Icons.Rounded.AddAPhoto, null) },
                    label = { Text(stringResource(R.string.add)) },
                )
                NavigationBarItem(
                    selected = false,
                    enabled = !doc?.pages.isNullOrEmpty(),
                    onClick = { actions.openReview(route.docId) },
                    icon = { Icon(Icons.Rounded.Edit, null) },
                    label = { Text(stringResource(R.string.edit)) },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Dimens.bottomNav))
    }

    if (exportSheet && doc != null) {
        ExportSheet(
            pageCount = doc.pages.size,
            defaults = settings,
            onDismiss = { exportSheet = false },
            onShare = { format, profile, size ->
                exportSheet = false
                val req = ExportRequest(route.docId, format, profile, size)
                vm.rememberDefaults(req)
                vm.share(req)
            },
            onSave = { format, profile, size ->
                exportSheet = false
                val req = ExportRequest(route.docId, format, profile, size)
                vm.rememberDefaults(req)
                vm.rememberForSave(req)
                if (format == ExportFormat.PDF) pdfPicker.launch(Titles.safeFileName(doc.summary.title) + ".pdf")
                else folderPicker.launch(null)
            },
        )
    }

    if (renaming && doc != null) {
        var text by remember { mutableStateOf(doc.summary.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.document_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = text.isBlank(),
                    supportingText = { if (text.isBlank()) Text(stringResource(R.string.name_required)) },
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { vm.rename(text); renaming = false }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.delete_doc_title)) },
            text = { Text(stringResource(R.string.delete_doc_body)) },
            confirmButton = {
                TextButton(onClick = { confirmTrash = false; vm.trash() }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** components.md "PdfPageView": white pages on the sunken desk, 16dp apart, with a page counter pill. */
@Composable
private fun PageList(doc: DocumentDetail) {
    val listState = rememberLazyListState()
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = Dimens.space6, vertical = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(doc.pages, key = { _, p -> p.id }) { index, page ->
                val label = stringResource(R.string.page_n, index + 1)
                Surface(
                    shadowElevation = Dimens.elevPage,
                    shape = MaterialTheme.shapes.extraSmall,
                    color = ScannerTheme.colors.page,
                    modifier = Modifier.widthIn(max = Dimens.contentMax).fillMaxWidth(),
                ) {
                    BlobImage(
                        sha = page.processedSha ?: page.thumbSha,
                        contentDescription = label,
                        modifier = Modifier.fillMaxWidth().aspectRatio(pageAspect(page)),
                        maxEdge = 1600,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.page_counter, current, doc.pages.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = Dimens.space2)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim)
                .padding(horizontal = Dimens.space3, vertical = Dimens.space1),
        )
    }
}

/** Width / height of the rendered page, falling back to A4 portrait while it renders. */
private fun pageAspect(page: com.docscanner.app.model.Page): Float {
    val q = page.quad ?: com.docscanner.app.model.Quad.FULL
    if (page.originalWidth == 0) return 1f / 1.414f
    val w = page.originalWidth.toFloat()
    val h = page.originalHeight.toFloat()
    val (uw, uh) = if (page.sourceRotation % 180 == 0) w to h else h to w
    val top = kotlin.math.hypot((q.tr.x - q.tl.x) * uw, (q.tr.y - q.tl.y) * uh)
    val left = kotlin.math.hypot((q.bl.x - q.tl.x) * uw, (q.bl.y - q.tl.y) * uh)
    if (top <= 0f || left <= 0f) return 1f / 1.414f
    val aspect = top / left
    return if (page.rotation % 180 == 0) aspect else 1f / aspect
}

