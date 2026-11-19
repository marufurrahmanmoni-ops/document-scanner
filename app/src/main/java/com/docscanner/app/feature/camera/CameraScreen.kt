package com.docscanner.app.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.designsystem.components.BlobImage
import com.docscanner.app.designsystem.components.ButtonVariant
import com.docscanner.app.designsystem.components.EmptyState
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.feature.common.rememberGalleryImport
import com.docscanner.app.feature.common.rememberImportViewModel
import com.docscanner.app.imaging.EdgeState
import com.docscanner.app.model.Quad
import com.docscanner.app.model.ScanMode
import com.docscanner.app.navigation.CameraRoute
import com.docscanner.app.navigation.NavActions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** S07 + S07a: live preview with edge overlay, auto-capture, single/batch, gallery import, permission states. */
@Composable
fun CameraScreen(route: CameraRoute, actions: NavActions) {
    val container = LocalAppContainer.current
    val vm: CameraViewModel = viewModel { CameraViewModel(container, route) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // The camera keeps the sensor's natural orientation and rotates nothing (screens.md S07).
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (previous != null) activity?.requestedOrientation = previous }
    }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is CameraEvent.OpenCrop -> actions.openCrop(e.docId, e.pageId, e.flow, replaceCamera = route.replacePageId != null)
                is CameraEvent.OpenReview -> actions.openReview(e.docId)
                CameraEvent.Close -> actions.closeCamera()
            }
        }
    }

    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (vm.needsDiscardConfirm()) {
            confirmDiscard = true
        } else {
            actions.closeCamera()
        }
    }
    BackHandler { close() }

    val importVm = rememberImportViewModel("camera")
    val importing by importVm.busy.collectAsStateWithLifecycle()
    val openGallery = rememberGalleryImport(importVm, targetDocId = ui.docId) { r ->
        r.onSuccess { actions.routeImport(it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ScannerTheme.colors.cameraBg),
    ) {
        if (!granted) {
            PermissionNeeded(
                permanentlyDenied = askedOnce && activity != null &&
                    !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
                onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onImport = openGallery,
                onClose = close,
            )
            // Re-check when the user comes back from system settings.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
        } else {
            LiveCamera(vm = vm, ui = ui, onClose = close, onGallery = openGallery)
        }

        if (ui.saving || importing != null) {
            Surface(
                color = ScannerTheme.colors.cameraScrim,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Row(Modifier.padding(Dimens.space4), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(Dimens.iconSm), color = ScannerTheme.colors.cameraText, strokeWidth = Dimens.focusRing)
                    Text(
                        stringResource(R.string.saving_page),
                        color = ScannerTheme.colors.cameraText,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = Dimens.space3),
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(pluralStringResource(R.plurals.discard_body, ui.sessionPages, ui.sessionPages)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.discardAndClose() }) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_scanning)) }
            },
        )
    }
}

@Composable
private fun LiveCamera(vm: CameraViewModel, ui: CameraUiState, onClose: () -> Unit, onGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val edge by vm.edge.collectAsStateWithLifecycle()

    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var bindError by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            runCatching {
                val p = providerFuture.get()
                provider = p
                val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio43)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, DocumentAnalyzer(vm::onFrame)) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio43)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .build()
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                imageCapture = capture
            }.onFailure { bindError = true }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    // Flash and torch follow the UI state.
    LaunchedEffect(ui.flash, imageCapture, camera) {
        imageCapture?.flashMode = when (ui.flash) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(ui.flash == FlashMode.TORCH)
    }

    fun capture() {
        val ic = imageCapture ?: return
        if (vm.isBusy()) return
        val hint = vm.currentQuad()
        vm.onCaptureStarted()
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bytes = try {
                    val buf = image.planes[0].buffer
                    buf.rewind()
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    image.close()
                }
                vm.onCaptured(bytes, rotation, hint)
            }

            override fun onError(exception: ImageCaptureException) {
                vm.onCaptureFailed()
            }
        })
    }

    LaunchedEffect(vm, imageCapture) {
        vm.autoShutter.collect { capture() }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Top bar: close, flash, auto-capture
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.space3, vertical = Dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraIconButton(Icons.Rounded.Close, stringResource(R.string.close_camera), onClick = onClose)
            Spacer(Modifier.weight(1f))
            val (flashIcon, flashLabel) = when (ui.flash) {
                FlashMode.OFF -> Icons.Rounded.FlashOff to R.string.flash_off
                FlashMode.AUTO -> Icons.Rounded.FlashAuto to R.string.flash_auto
                FlashMode.ON -> Icons.Rounded.FlashOn to R.string.flash_on
                FlashMode.TORCH -> Icons.Rounded.FlashlightOn to R.string.flash_torch
            }
            CameraIconButton(flashIcon, stringResource(flashLabel), onClick = vm::cycleFlash, active = ui.flash != FlashMode.OFF)
            Spacer(Modifier.size(Dimens.space2))
            AutoToggle(on = ui.autoCapture, onClick = vm::toggleAutoCapture)
        }

        // Preview + overlay share one 3:4 box so normalised coordinates map exactly.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(3f / 4f)) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                EdgeOverlay(edge, Modifier.fillMaxSize())
                if (bindError) {
                    Text(
                        stringResource(R.string.camera_unavailable),
                        color = ScannerTheme.colors.cameraText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(Dimens.space6),
                    )
                }
                val hint = when {
                    ui.error -> R.string.hint_capture_failed
                    edge is EdgeState.Searching -> R.string.hint_point
                    else -> R.string.hint_hold
                }
                Text(
                    stringResource(hint),
                    color = ScannerTheme.colors.cameraText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Dimens.space4)
                        .clip(CircleShape)
                        .background(ScannerTheme.colors.cameraScrim)
                        .padding(horizontal = Dimens.space4, vertical = Dimens.space1 + Dimens.space0_5)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        // Mode strip
        if (!ui.modeLocked) {
            Row(
                Modifier.fillMaxWidth().padding(top = Dimens.space3),
                horizontalArrangement = Arrangement.Center,
            ) {
                ModeChip(stringResource(R.string.mode_single), ui.mode == ScanMode.SINGLE) { vm.setMode(ScanMode.SINGLE) }
                Spacer(Modifier.size(Dimens.space6))
                ModeChip(stringResource(R.string.mode_batch), ui.mode == ScanMode.BATCH) { vm.setMode(ScanMode.BATCH) }
            }
        }

        // Capture bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                CameraIconButton(Icons.Rounded.PhotoLibrary, stringResource(R.string.import_photos), onClick = onGallery)
            }
            Shutter(autoArmed = ui.autoCapture && edge is EdgeState.Locked, busy = ui.saving, onClick = {
                vm.onShutterPressed()
                capture()
            })
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (ui.mode == ScanMode.BATCH && ui.sessionPages > 0) {
                    PageStack(count = ui.sessionPages, thumbSha = ui.lastThumbSha, onClick = vm::onDone)
                }
            }
        }
    }
}

/** components.md "EdgeOverlay": brackets while searching, blue quad when found, green when locked. */
@Composable
private fun EdgeOverlay(state: EdgeState, modifier: Modifier) {
    val colors = ScannerTheme.colors
    Canvas(modifier) {
        fun pt(p: com.docscanner.app.model.NPoint) = Offset(p.x * size.width, p.y * size.height)
        when (state) {
            EdgeState.Searching -> {
                val l = size.minDimension * 0.08f
                val m = size.minDimension * 0.1f
                val c = colors.edgeSearching.copy(alpha = 0.6f)
                val sw = Dimens.edgeStroke.toPx()
                listOf(
                    Offset(m, m) to listOf(Offset(m + l, m), Offset(m, m + l)),
                    Offset(size.width - m, m) to listOf(Offset(size.width - m - l, m), Offset(size.width - m, m + l)),
                    Offset(size.width - m, size.height - m) to listOf(Offset(size.width - m - l, size.height - m), Offset(size.width - m, size.height - m - l)),
                    Offset(m, size.height - m) to listOf(Offset(m + l, size.height - m), Offset(m, size.height - m - l)),
                ).forEach { (corner, ends) -> ends.forEach { drawLine(c, corner, it, sw) } }
            }
            is EdgeState.Detected, is EdgeState.Locked -> {
                val quad: Quad = if (state is EdgeState.Detected) state.quad else (state as EdgeState.Locked).quad
                val stroke = if (state is EdgeState.Locked) colors.edgeLocked else colors.edgeDetected
                val path = Path().apply {
                    val p = quad.points.map { pt(it) }
                    moveTo(p[0].x, p[0].y)
                    p.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, colors.edgeFill)
                drawPath(path, colors.edgeHalo, style = Stroke(Dimens.edgeHaloStroke.toPx()))
                drawPath(path, stroke, style = Stroke(Dimens.edgeStroke.toPx()))
                quad.points.forEach { drawCircle(stroke, Dimens.cornerDot.toPx() / 2, pt(it)) }
            }
        }
    }
}

@Composable
private fun CameraIconButton(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(Dimens.touchMin)
            .clip(CircleShape)
            .background(ScannerTheme.colors.cameraScrim),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) ScannerTheme.colors.edgeLocked else ScannerTheme.colors.cameraText,
        )
    }
}

@Composable
private fun AutoToggle(on: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (on) R.string.auto_capture_on else R.string.auto_capture_off)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = ScannerTheme.colors.cameraScrim,
        modifier = Modifier.semantics { contentDescription = label; role = Role.Switch },
    ) {
        Text(
            stringResource(R.string.auto_short),
            color = if (on) ScannerTheme.colors.edgeLocked else ScannerTheme.colors.cameraTextMuted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space3),
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Dimens.space3, vertical = Dimens.space2),
    ) {
        Text(
            label,
            color = if (selected) ScannerTheme.colors.cameraText else ScannerTheme.colors.cameraTextMuted,
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        )
        Box(
            Modifier
                .padding(top = Dimens.space1)
                .size(Dimens.space1)
                .clip(CircleShape)
                .background(if (selected) ScannerTheme.colors.cameraText else ScannerTheme.colors.cameraBg),
        )
    }
}

@Composable
private fun Shutter(autoArmed: Boolean, busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.take_photo)
    Box(
        Modifier
            .size(Dimens.shutter)
            .clip(CircleShape)
            .border(Dimens.space1, ScannerTheme.colors.cameraText, CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Dimens.shutterInner)
                .clip(CircleShape)
                .background(ScannerTheme.colors.cameraText)
                .border(Dimens.space1 - Dimens.hairline, if (autoArmed) ScannerTheme.colors.edgeLocked else ScannerTheme.colors.cameraText, CircleShape),
        )
        if (busy) CircularProgressIndicator(Modifier.size(Dimens.shutter), color = ScannerTheme.colors.edgeDetected, strokeWidth = Dimens.focusRing)
    }
}

@Composable
private fun PageStack(count: Int, thumbSha: String?, onClick: () -> Unit) {
    val label = stringResource(R.string.review_pages_a11y, count)
    Box(
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(Dimens.space2),
    ) {
        Box(
            Modifier
                .offset(x = Dimens.space1, y = -Dimens.space1)
                .size(Dimens.stackThumbW, Dimens.stackThumbH)
                .background(ScannerTheme.colors.cameraTextMuted, MaterialTheme.shapes.extraSmall),
        )
        BlobImage(
            sha = thumbSha,
            contentDescription = null,
            modifier = Modifier
                .size(Dimens.stackThumbW, Dimens.stackThumbH)
                .clip(MaterialTheme.shapes.extraSmall),
            maxEdge = 256,
        )
        Text(
            count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = Dimens.space3, y = -Dimens.space3)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = Dimens.space2, vertical = Dimens.space0_5),
        )
        Text(
            stringResource(R.string.done),
            color = ScannerTheme.colors.cameraText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = Dimens.space6),
        )
    }
}

@Composable
private fun PermissionNeeded(
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(Dimens.space3)) {
            CameraIconButton(Icons.Rounded.Close, stringResource(R.string.close_camera), onClick = onClose)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(Dimens.space6)) {
                EmptyState(
                    icon = Icons.Rounded.NoPhotography,
                    title = stringResource(R.string.camera_permission_title),
                    body = stringResource(if (permanentlyDenied) R.string.camera_permission_denied else R.string.camera_permission_body),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        if (permanentlyDenied) {
                            ScannerButton(stringResource(R.string.open_settings), onOpenSettings)
                        } else {
                            ScannerButton(stringResource(R.string.allow_camera), onAllow)
                        }
                        ScannerButton(stringResource(R.string.import_instead), onImport, variant = ButtonVariant.TEXT)
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
