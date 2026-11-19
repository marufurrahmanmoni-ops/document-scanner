package com.docscanner.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.BuildConfig
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.data.Settings
import com.docscanner.app.designsystem.components.SectionHeader
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.feature.editor.filterLabel
import com.docscanner.app.model.Filter
import com.docscanner.app.model.PageSize
import com.docscanner.app.model.QualityProfile
import com.docscanner.app.model.ScanMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setMode(m: ScanMode) = viewModelScope.launch { container.settings.setDefaultMode(m) }
    fun setAuto(on: Boolean) = viewModelScope.launch { container.settings.setAutoCapture(on) }
    fun setFilter(f: Filter) = viewModelScope.launch { container.settings.setDefaultFilter(f) }
    fun setQuality(q: QualityProfile) = viewModelScope.launch { container.settings.setExportQuality(q) }
    fun setPageSize(p: PageSize) = viewModelScope.launch { container.settings.setPageSize(p) }
}

private data class Option<T>(val value: T, val label: Int)

/** S19 Settings: every row here changes real behaviour. Sections arrive with their features. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onOpenAccount: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val s by vm.settings.collectAsStateWithLifecycle()
    var chooser by remember { mutableStateOf<String?>(null) }
    var licences by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + Dimens.space12),
    ) {
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = Dimens.gutterCompact, vertical = Dimens.space4).semantics { heading() },
        )

        SectionHeader(stringResource(R.string.settings_account), Modifier.padding(horizontal = Dimens.gutterCompact))
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_title)) },
            supportingContent = { Text(stringResource(R.string.account_row_desc)) },
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenAccount),
        )

        SectionHeader(stringResource(R.string.settings_scanning), Modifier.padding(horizontal = Dimens.gutterCompact))
        ValueRow(stringResource(R.string.default_mode), stringResource(modeLabel(s.defaultMode))) { chooser = "mode" }
        SwitchRow(stringResource(R.string.auto_capture), stringResource(R.string.auto_capture_desc), s.autoCapture, vm::setAuto)
        ValueRow(stringResource(R.string.default_filter), stringResource(filterLabel(s.defaultFilter))) { chooser = "filter" }

        SectionHeader(stringResource(R.string.settings_documents), Modifier.padding(horizontal = Dimens.gutterCompact))
        ValueRow(stringResource(R.string.default_quality), stringResource(qualityLabel(s.exportQuality))) { chooser = "quality" }
        ValueRow(stringResource(R.string.default_page_size), stringResource(sizeLabel(s.pageSize))) { chooser = "size" }

        SectionHeader(stringResource(R.string.settings_privacy), Modifier.padding(horizontal = Dimens.gutterCompact))
        ListItem(
            headlineContent = { Text(stringResource(R.string.privacy_local)) },
            supportingContent = { Text(stringResource(R.string.privacy_local_desc)) },
        )

        SectionHeader(stringResource(R.string.settings_about), Modifier.padding(horizontal = Dimens.gutterCompact))
        ListItem(
            headlineContent = { Text(stringResource(R.string.version)) },
            supportingContent = { Text(BuildConfig.VERSION_NAME) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.licences)) },
            modifier = Modifier.clickable { licences = true },
        )
    }

    when (chooser) {
        "mode" -> ChooserDialog(
            stringResource(R.string.default_mode),
            listOf(Option(ScanMode.SINGLE, R.string.mode_single), Option(ScanMode.BATCH, R.string.mode_batch)),
            s.defaultMode,
            { vm.setMode(it); chooser = null },
            { chooser = null },
        )
        "filter" -> ChooserDialog(
            stringResource(R.string.default_filter),
            Filter.entries.map { Option(it, filterLabel(it)) },
            s.defaultFilter,
            { vm.setFilter(it); chooser = null },
            { chooser = null },
        )
        "quality" -> ChooserDialog(
            stringResource(R.string.default_quality),
            QualityProfile.entries.map { Option(it, qualityLabel(it)) },
            s.exportQuality,
            { vm.setQuality(it); chooser = null },
            { chooser = null },
        )
        "size" -> ChooserDialog(
            stringResource(R.string.default_page_size),
            PageSize.entries.map { Option(it, sizeLabel(it)) },
            s.pageSize,
            { vm.setPageSize(it); chooser = null },
            { chooser = null },
        )
    }

    if (licences) {
        AlertDialog(
            onDismissRequest = { licences = false },
            title = { Text(stringResource(R.string.licences)) },
            text = { Text(stringResource(R.string.licences_body)) },
            confirmButton = { TextButton(onClick = { licences = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(desc) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun <T> ChooserDialog(title: String, options: List<Option<T>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    ListItem(
                        headlineContent = { Text(stringResource(o.label)) },
                        leadingContent = { RadioButton(selected = o.value == selected, onClick = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = o.value == selected, role = Role.RadioButton, onClick = { onPick(o.value) }),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun modeLabel(m: ScanMode) = if (m == ScanMode.SINGLE) R.string.mode_single else R.string.mode_batch

private fun qualityLabel(q: QualityProfile) = when (q) {
    QualityProfile.SMALL -> R.string.quality_small
    QualityProfile.BALANCED -> R.string.quality_balanced
    QualityProfile.ORIGINAL -> R.string.quality_original
}

private fun sizeLabel(p: PageSize) = when (p) {
    PageSize.FIT -> R.string.size_fit
    PageSize.A4 -> R.string.size_a4
    PageSize.LETTER -> R.string.size_letter
}

