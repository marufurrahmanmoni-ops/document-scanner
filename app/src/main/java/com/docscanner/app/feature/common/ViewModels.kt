package com.docscanner.app.feature.common

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.LocalAppContainer

@Composable
fun rememberImportViewModel(key: String? = null): ImportViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = key) { ImportViewModel(container) }
}

/**
 * Wires the Android Photo Picker (no storage permission needed) to an [ImportViewModel].
 * Returns a function that opens the picker.
 */
@Composable
fun rememberGalleryImport(
    vm: ImportViewModel,
    targetDocId: String?,
    onOutcome: (Result<ImportOutcome>) -> Unit,
): () -> Unit {
    val resolver: ContentResolver = LocalContext.current.contentResolver
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50),
    ) { uris: List<Uri> -> vm.import(uris, resolver, targetDocId) }
    val latest by rememberUpdatedState(onOutcome)
    LaunchedEffect(vm) {
        vm.events.collect { latest(it) }
    }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
