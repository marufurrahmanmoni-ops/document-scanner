package com.docscanner.app.feature.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.docscanner.app.R
import com.docscanner.app.data.ExportFormat
import com.docscanner.app.data.Settings
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.ButtonVariant
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.feature.common.pagesLabel
import com.docscanner.app.model.PageSize
import com.docscanner.app.model.QualityProfile

/** S16 Share / export: format, quality, page size; Save to device or Share. No watermark, ever. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    pageCount: Int,
    defaults: Settings,
    onDismiss: () -> Unit,
    onShare: (ExportFormat, QualityProfile, PageSize) -> Unit,
    onSave: (ExportFormat, QualityProfile, PageSize) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var quality by remember { mutableStateOf(defaults.exportQuality) }
    var size by remember { mutableStateOf(defaults.pageSize) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.space6).padding(bottom = Dimens.space6),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            Text(pagesLabel(pageCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Choice(
                label = stringResource(R.string.format),
                options = listOf(ExportFormat.PDF to R.string.format_pdf, ExportFormat.JPG to R.string.format_jpg),
                selected = format,
                onSelect = { format = it },
            )
            Choice(
                label = stringResource(R.string.quality),
                options = listOf(
                    QualityProfile.SMALL to R.string.quality_small,
                    QualityProfile.BALANCED to R.string.quality_balanced,
                    QualityProfile.ORIGINAL to R.string.quality_original,
                ),
                selected = quality,
                onSelect = { quality = it },
            )
            if (format == ExportFormat.PDF) {
                Choice(
                    label = stringResource(R.string.page_size),
                    options = listOf(PageSize.FIT to R.string.size_fit, PageSize.A4 to R.string.size_a4, PageSize.LETTER to R.string.size_letter),
                    selected = size,
                    onSelect = { size = it },
                )
            }
            Text(
                stringResource(R.string.no_watermark_note),
                style = MaterialTheme.typography.bodySmall,
                color = ScannerTheme.colors.textSubtle,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                ScannerButton(
                    text = stringResource(R.string.save_to_device),
                    onClick = { onSave(format, quality, size) },
                    variant = ButtonVariant.SECONDARY,
                    size = ButtonSize.LG,
                    icon = Icons.Rounded.SaveAlt,
                    modifier = Modifier.weight(1f),
                )
                ScannerButton(
                    text = stringResource(R.string.share),
                    onClick = { onShare(format, quality, size) },
                    size = ButtonSize.LG,
                    icon = Icons.Rounded.Share,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, textRes) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    label = { Text(stringResource(textRes)) },
                )
            }
        }
    }
}
