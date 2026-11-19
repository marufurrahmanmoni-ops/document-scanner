package com.docscanner.app.feature.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.docscanner.app.model.NPoint
import com.docscanner.app.model.Quad
import com.docscanner.app.model.QuadSource
import com.docscanner.app.navigation.CropRoute
import com.docscanner.app.navigation.EditFlow
import com.docscanner.app.navigation.NavActions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.hypot

data class CropUiState(
    val image: Bitmap? = null,
    val quad: Quad = Quad.FULL,
    val source: QuadSource = QuadSource.AUTO,
    val detecting: Boolean = false,
    val noEdgesFound: Boolean = false,
    val saving: Boolean = false,
    val loadFailed: Boolean = false,
)

class CropViewModel(private val container: AppContainer, private val route: CropRoute) : ViewModel() {
    private val _ui = MutableStateFlow(CropUiState())
    val ui: StateFlow<CropUiState> = _ui.asStateFlow()

    private val _done = Channel<Boolean>(Channel.BUFFERED)  // true = next, false = left (retake)
    val done = _done.receiveAsFlow()

    init {
        viewModelScope.launch {
            val page = container.documents.page(route.pageId)
            val img = runCatching { container.documents.uprightOriginal(route.pageId) }.getOrNull()
            if (page == null || img == null) {
                _ui.update { it.copy(loadFailed = true) }
                return@launch
            }
            _ui.update {
                it.copy(
                    image = img,
                    quad = page.quad ?: Quad.FULL,
                    source = page.quadSource,
                    noEdgesFound = page.quad == null,
                )
            }
        }
    }

    fun moveCorner(index: Int, p: NPoint) {
        val clamped = NPoint(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
        _ui.update { it.copy(quad = it.quad.with(index, clamped), source = QuadSource.MANUAL, noEdgesFound = false) }
    }

    fun autoDetect() {
        if (_ui.value.detecting) return
        viewModelScope.launch {
            _ui.update { it.copy(detecting = true) }
            val q = container.documents.redetect(route.pageId)
            _ui.update {
                if (q != null) it.copy(quad = q, source = QuadSource.AUTO, detecting = false, noEdgesFound = false)
                else it.copy(detecting = false, noEdgesFound = true)
            }
        }
    }

    fun fullPage() {
        _ui.update { it.copy(quad = Quad.FULL, source = QuadSource.FULL, noEdgesFound = false) }
    }

    fun next() {
        val s = _ui.value
        if (!s.quad.isValid() || s.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            val stored = if (s.source == QuadSource.FULL) null else s.quad
            container.documents.setQuad(route.pageId, stored, s.source)
            _ui.update { it.copy(saving = false) }
            _done.send(true)
        }
    }

    /** In a fresh capture (single / review flows), going back means "retake": the page is removed. */
    fun leave() {
        viewModelScope.launch {
            if (route.flow != EditFlow.EDIT) container.documents.deletePage(route.pageId)
            _done.send(false)
        }
    }
}

/** S09 Adjust edges: drag four corners with a magnifier, re-detect, or use the full photo. */
@Composable
fun CropScreen(route: CropRoute, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: CropViewModel = viewModel { CropViewModel(container, route) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.done.collect { next ->
            if (next) actions.openEnhance(route.docId, route.pageId, route.flow) else actions.back()
        }
    }
    BackHandler { vm.leave() }

    val colors = ScannerTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.cameraBg)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.space2, vertical = Dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::leave) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(if (route.flow == EditFlow.EDIT) R.string.back else R.string.retake),
                    tint = colors.cameraText,
                )
            }
            Text(
                stringResource(R.string.adjust_edges),
                style = MaterialTheme.typography.titleLarge,
                color = colors.cameraText,
                modifier = Modifier.weight(1f),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(Dimens.space6), contentAlignment = Alignment.Center) {
            val image = ui.image
            when {
                ui.loadFailed -> Text(stringResource(R.string.page_load_failed), color = colors.cameraText)
                image == null -> CircularProgressIndicator(color = colors.cameraText)
                else -> CropCanvas(image, ui.quad, vm::moveCorner)
            }
        }

        val hint = when {
            !ui.quad.isValid() -> R.string.crop_invalid
            ui.noEdgesFound -> R.string.crop_no_edges
            else -> null
        }
        if (hint != null) {
            Text(
                stringResource(hint),
                color = colors.cameraText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = Dimens.space6)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(Dimens.space4),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = vm::autoDetect, enabled = !ui.detecting && ui.image != null) {
                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = colors.cameraText)
                Text(stringResource(R.string.auto_detect), color = colors.cameraText, modifier = Modifier.padding(start = Dimens.space2))
            }
            TextButton(onClick = vm::fullPage, enabled = ui.image != null) {
                Icon(Icons.Rounded.CropFree, contentDescription = null, tint = colors.cameraText)
                Text(stringResource(R.string.full_page), color = colors.cameraText, modifier = Modifier.padding(start = Dimens.space2))
            }
            Box(Modifier.weight(1f))
            ScannerButton(
                text = stringResource(R.string.next),
                onClick = vm::next,
                size = ButtonSize.LG,
                enabled = ui.image != null && ui.quad.isValid(),
                loading = ui.saving,
            )
        }
    }
}

/** components.md "CropCanvas": the image with a dimmed outside, an accent quad and four 48dp-target handles. */
@Composable
private fun CropCanvas(bitmap: Bitmap, quad: Quad, onMove: (Int, NPoint) -> Unit) {
    val image: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val latestQuad by rememberUpdatedState(quad)
    var active by remember { mutableIntStateOf(-1) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val colors = ScannerTheme.colors
    val accent = MaterialTheme.colorScheme.primary
    val handle = Dimens.cropHandle
    val hit = Dimens.cropHandleHit
    val loupe = Dimens.space16 + Dimens.space12

    Box(Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val pts = currentQuadPoints(latestQuad, size.width.toFloat(), size.height.toFloat())
                            val best = pts.withIndex().minBy { (_, p) -> hypot(p.x - start.x, p.y - start.y) }
                            active = if (hypot(best.value.x - start.x, best.value.y - start.y) <= hit.toPx()) best.index else -1
                            touch = start
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { change, _ ->
                            if (active >= 0) {
                                change.consume()
                                touch = change.position
                                onMove(active, NPoint(change.position.x / size.width, change.position.y / size.height))
                            }
                        },
                    )
                },
        ) {
            val pts = currentQuadPoints(quad, size.width, size.height)
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            // Dim everything outside the quad.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addPath(path)
            }
            drawPath(outside, colors.cameraScrim)
            drawPath(path, accent, style = Stroke(Dimens.focusRing.toPx()))
            pts.forEachIndexed { i, p ->
                drawCircle(colors.cameraText, handle.toPx() / 2, p)
                drawCircle(accent, handle.toPx() / 2, p, style = Stroke(if (i == active) Dimens.space1.toPx() else Dimens.focusRing.toPx()))
            }
            // Magnifier while dragging, on the side away from the finger.
            if (active >= 0) {
                val r = loupe.toPx() / 2
                val center = Offset(if (touch.x < size.width / 2) size.width - r - 8f else r + 8f, r + 8f)
                val zoom = 2.5f
                val srcSizePx = (2 * r / zoom)
                val sx = image.width / size.width
                val sy = image.height / size.height
                val src = IntOffset(((touch.x - srcSizePx / 2) * sx).toInt(), ((touch.y - srcSizePx / 2) * sy).toInt())
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
                clipPath(circle) {
                    drawRect(colors.cameraBg, center - Offset(r, r), Size(2 * r, 2 * r))
                    drawImage(
                        image,
                        srcOffset = IntOffset(src.x.coerceIn(0, image.width - 1), src.y.coerceIn(0, image.height - 1)),
                        srcSize = IntSize(
                            (srcSizePx * sx).toInt().coerceAtMost(image.width - src.x.coerceIn(0, image.width - 1)).coerceAtLeast(1),
                            (srcSizePx * sy).toInt().coerceAtMost(image.height - src.y.coerceIn(0, image.height - 1)).coerceAtLeast(1),
                        ),
                        dstOffset = IntOffset((center.x - r).toInt(), (center.y - r).toInt()),
                        dstSize = IntSize((2 * r).toInt(), (2 * r).toInt()),
                    )
                    drawLine(accent, center - Offset(12f, 0f), center + Offset(12f, 0f), 2f)
                    drawLine(accent, center - Offset(0f, 12f), center + Offset(0f, 12f), 2f)
                }
                drawPath(circle, colors.cameraText, style = Stroke(Dimens.focusRing.toPx()))
            }
        }
    }
}

private fun currentQuadPoints(q: Quad, w: Float, h: Float): List<Offset> = q.points.map { Offset(it.x * w, it.y * h) }
