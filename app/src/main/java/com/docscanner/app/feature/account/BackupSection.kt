package com.docscanner.app.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.cloud.Account
import com.docscanner.app.cloud.sync.BackupState
import com.docscanner.app.cloud.sync.SyncPreconditionException
import com.docscanner.app.cloud.sync.VaultCrypto
import com.docscanner.app.cloud.sync.VaultRepository
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.ButtonVariant
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.components.SectionHeader
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.feature.common.shortDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class VaultStatus { CHECKING, OFFLINE, NEEDS_CREATE, NEEDS_UNLOCK, READY }

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<BackupState> = c.syncSettings.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupState())
    val pending: StateFlow<Int> = c.database.outbox().observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val vaultStatus = MutableStateFlow(VaultStatus.CHECKING)
    val busy = MutableStateFlow(false)
    private val _msg = Channel<Int>(Channel.BUFFERED)
    val messages = _msg.receiveAsFlow()

    fun checkVault(uid: String) = viewModelScope.launch {
        if (c.vault.localKey() != null) { vaultStatus.value = VaultStatus.READY; return@launch }
        vaultStatus.value = VaultStatus.CHECKING
        vaultStatus.value = runCatching { c.vault.remoteVaultExists(uid) }
            .map { if (it) VaultStatus.NEEDS_UNLOCK else VaultStatus.NEEDS_CREATE }
            .getOrDefault(VaultStatus.OFFLINE)
    }

    private fun work(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { block() } finally { busy.value = false }
        }
    }

    fun createPassphrase(uid: String, pass: String) = work {
        runCatching { c.vault.create(uid, pass.toCharArray()) }
            .onSuccess { vaultStatus.value = VaultStatus.READY; turnOn(uid) }
            .onFailure { _msg.send(R.string.backup_err_generic) }
    }

    fun unlock(uid: String, pass: String) = work {
        runCatching { c.vault.unlock(uid, pass.toCharArray()) }
            .onSuccess { vaultStatus.value = VaultStatus.READY; turnOn(uid); _msg.send(R.string.backup_restoring) }
            .onFailure { _msg.send(if (it is VaultCrypto.WrongPassphraseException) R.string.backup_wrong_pass else R.string.backup_err_generic) }
    }

    /** Called only after the consent dialog was accepted and the key is unlocked. */
    private suspend fun turnOn(uid: String) {
        c.syncSettings.enable(uid, System.currentTimeMillis())
        c.syncEngine?.enqueueEverything()
        val wifi = c.syncSettings.current().wifiOnly
        c.syncScheduler.startPeriodic(wifi)
        c.syncScheduler.requestSoon(wifi, delaySeconds = 0)
    }

    fun turnOnWithExistingKey(uid: String) = work { turnOn(uid) }

    fun turnOff() = work {
        c.syncSettings.disable()
        c.syncScheduler.stopAll()
        _msg.send(R.string.backup_paused)
    }

    fun setWifiOnly(on: Boolean) = work {
        c.syncSettings.setWifiOnly(on)
        if (state.value.enabled) c.syncScheduler.startPeriodic(on)
    }

    fun syncNow() = work { doSync() }

    private suspend fun doSync() {
        val r = runCatching { c.syncEngine!!.syncOnce() }
        r.onSuccess { _msg.send(if (it.conflicts > 0) R.string.backup_synced_conflicts else R.string.backup_synced) }
        r.onFailure { e ->
            c.syncSettings.recordError(e.javaClass.simpleName)
            _msg.send(
                when ((e as? SyncPreconditionException)?.reason) {
                    SyncPreconditionException.Reason.EMAIL_NOT_VERIFIED -> R.string.backup_need_verify
                    SyncPreconditionException.Reason.WRONG_ACCOUNT -> R.string.backup_wrong_account
                    null -> R.string.err_network
                    else -> R.string.backup_err_generic
                },
            )
        }
    }

    /** Pulls everything in the cloud again from the start (missing pages and documents come back). */
    fun restoreAll() = work {
        c.syncSettings.resetCursor()
        _msg.send(R.string.backup_restoring)
        doSync()
    }

    fun clearConflicts() = viewModelScope.launch { c.syncSettings.clearConflicts() }

    fun changePassphrase(uid: String, pass: String) = work {
        runCatching { c.vault.changePassphrase(uid, pass.toCharArray()) }
            .onSuccess { _msg.send(R.string.backup_pass_changed) }
            .onFailure { _msg.send(R.string.backup_err_generic) }
    }
}

/** "Encrypted backup" section of the account screen. Nothing is uploaded until the user agrees here. */
@Composable
fun BackupSection(account: Account, showMessage: suspend (Int) -> Unit) {
    val container = LocalAppContainer.current
    val vm: BackupViewModel = viewModel { BackupViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val vault by vm.vaultStatus.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val running by remember { container.syncScheduler.observeRunning() }.collectAsStateWithLifecycle(false)
    var consent by remember { mutableStateOf(false) }
    var changePass by remember { mutableStateOf(false) }

    LaunchedEffect(account.uid) { vm.checkVault(account.uid) }
    LaunchedEffect(vm) { vm.messages.collect { showMessage(it) } }

    SectionHeader(stringResource(R.string.backup_title))
    Text(stringResource(R.string.backup_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val needsVerify = account.usesPassword && !account.emailVerified
    when {
        needsVerify -> Text(stringResource(R.string.backup_need_verify), style = MaterialTheme.typography.bodyMedium)
        vault == VaultStatus.CHECKING -> Text(stringResource(R.string.backup_checking), style = MaterialTheme.typography.bodyMedium)
        vault == VaultStatus.OFFLINE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.err_network), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ScannerButton(stringResource(R.string.try_again), { vm.checkVault(account.uid) }, variant = ButtonVariant.TEXT)
        }
        !state.enabled -> ScannerButton(
            text = stringResource(R.string.backup_turn_on),
            onClick = { consent = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        else -> BackupOn(vm, state, pending, running, busy, onChangePass = { changePass = true })
    }

    if (consent) {
        ConsentAndKeyDialog(
            vault = vault,
            busy = busy,
            onDismiss = { consent = false },
            onCreate = { vm.createPassphrase(account.uid, it); consent = false },
            onUnlock = { vm.unlock(account.uid, it); consent = false },
            onAgreeOnly = { vm.turnOnWithExistingKey(account.uid); consent = false },
        )
    }
    if (changePass) {
        PassphraseDialog(
            title = stringResource(R.string.backup_change_pass),
            confirmText = stringResource(R.string.save),
            needConfirm = true,
            onDismiss = { changePass = false },
            onDone = { vm.changePassphrase(account.uid, it); changePass = false },
        )
    }
}

@Composable
private fun BackupOn(
    vm: BackupViewModel,
    state: BackupState,
    pending: Int,
    running: Boolean,
    busy: Boolean,
    onChangePass: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.backup_on)) },
        supportingContent = {
            Text(
                when {
                    running || busy -> stringResource(R.string.backup_syncing)
                    state.lastSyncAt == 0L -> stringResource(R.string.backup_never)
                    else -> stringResource(R.string.backup_last, shortDate(state.lastSyncAt))
                } + if (pending > 0) " · " + stringResource(R.string.backup_pending, pending) else "",
            )
        },
        trailingContent = { Switch(checked = true, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = true, role = Role.Switch, onValueChange = { vm.turnOff() }),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.backup_wifi_only)) },
        trailingContent = { Switch(checked = state.wifiOnly, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = state.wifiOnly, role = Role.Switch, onValueChange = vm::setWifiOnly),
    )
    state.lastError?.let {
        Text(stringResource(R.string.backup_last_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (state.conflicts > 0) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.padding(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.backup_conflicts, state.conflicts),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                ScannerButton(stringResource(R.string.ok), vm::clearConflicts, variant = ButtonVariant.TEXT, size = ButtonSize.SM)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ScannerButton(stringResource(R.string.backup_sync_now), vm::syncNow, loading = busy, size = ButtonSize.SM)
        ScannerButton(stringResource(R.string.backup_restore), vm::restoreAll, variant = ButtonVariant.SECONDARY, size = ButtonSize.SM, enabled = !busy)
    }
    ScannerButton(stringResource(R.string.backup_change_pass), onChangePass, variant = ButtonVariant.TEXT, enabled = !busy)
}

@Composable
private fun ConsentAndKeyDialog(
    vault: VaultStatus,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onAgreeOnly: () -> Unit,
) {
    var agreed by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val creating = vault == VaultStatus.NEEDS_CREATE
    val unlocking = vault == VaultStatus.NEEDS_UNLOCK
    val passOk = when {
        creating -> pass.length >= VaultRepository.MIN_PASSPHRASE && pass == confirm
        unlocking -> pass.isNotEmpty()
        else -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                Text(stringResource(R.string.backup_consent_body), style = MaterialTheme.typography.bodyMedium)
                if (creating || unlocking) {
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it.take(200) }, singleLine = true,
                        label = { Text(stringResource(if (creating) R.string.backup_new_pass else R.string.backup_enter_pass)) },
                        supportingText = { if (creating) Text(stringResource(R.string.backup_pass_rule)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                if (creating) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it.take(200) }, singleLine = true,
                        label = { Text(stringResource(R.string.backup_confirm_pass)) },
                        isError = confirm.isNotEmpty() && confirm != pass,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(stringResource(R.string.backup_pass_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(
                    Modifier.toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = agreed, onCheckedChange = null)
                    Text(stringResource(R.string.backup_consent_check), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Dimens.space2))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = agreed && passOk && !busy, onClick = {
                when {
                    creating -> onCreate(pass)
                    unlocking -> onUnlock(pass)
                    else -> onAgreeOnly()
                }
            }) { Text(stringResource(R.string.backup_turn_on)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun PassphraseDialog(title: String, confirmText: String, needConfirm: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = pass.length >= VaultRepository.MIN_PASSPHRASE && (!needConfirm || pass == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it.take(200) }, singleLine = true,
                    label = { Text(stringResource(R.string.backup_new_pass)) },
                    supportingText = { Text(stringResource(R.string.backup_pass_rule)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (needConfirm) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it.take(200) }, singleLine = true,
                        label = { Text(stringResource(R.string.backup_confirm_pass)) },
                        isError = confirm.isNotEmpty() && confirm != pass,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(pass) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
