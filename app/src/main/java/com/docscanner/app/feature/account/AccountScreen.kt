package com.docscanner.app.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscanner.app.AppContainer
import com.docscanner.app.LocalAppContainer
import com.docscanner.app.R
import com.docscanner.app.cloud.Account
import com.docscanner.app.cloud.AuthError
import com.docscanner.app.cloud.AuthException
import com.docscanner.app.cloud.CloudConfig
import com.docscanner.app.designsystem.components.ButtonSize
import com.docscanner.app.designsystem.components.ButtonVariant
import com.docscanner.app.designsystem.components.EmptyState
import com.docscanner.app.designsystem.components.ScannerButton
import com.docscanner.app.designsystem.components.SectionHeader
import com.docscanner.app.designsystem.theme.Dimens
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AccountViewModel(val container: AppContainer) : ViewModel() {
    val account: StateFlow<Account?> = container.auth.account
    val busy = MutableStateFlow(false)
    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Runs an auth call, shows its error (or [success] message) in the snackbar. */
    fun run(success: Int? = null, block: suspend () -> Result<*>) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val r = block()
            busy.value = false
            r.exceptionOrNull()?.let { _messages.send(errorText((it as? AuthException)?.error ?: AuthError.UNKNOWN)) }
                ?: success?.let { _messages.send(it) }
        }
    }

    fun refreshOnOpen() = viewModelScope.launch { if (account.value != null) container.auth.refresh() }
}

fun errorText(e: AuthError): Int = when (e) {
    AuthError.NOT_CONFIGURED -> R.string.err_not_configured
    AuthError.INVALID_CREDENTIALS -> R.string.err_invalid_credentials
    AuthError.EMAIL_IN_USE -> R.string.err_email_in_use
    AuthError.WEAK_PASSWORD -> R.string.err_weak_password
    AuthError.INVALID_EMAIL -> R.string.err_invalid_email
    AuthError.NETWORK -> R.string.err_network
    AuthError.TOO_MANY_ATTEMPTS -> R.string.err_too_many
    AuthError.CANCELLED -> R.string.err_cancelled
    AuthError.NO_GOOGLE_ACCOUNT -> R.string.err_no_google
    AuthError.RECENT_LOGIN_REQUIRED -> R.string.err_recent_login
    AuthError.UNKNOWN -> R.string.err_unknown
}

/**
 * Account: sign in / create account / reset password / Google, then profile, verification, backup,
 * subscription and account deletion. Sections that need cloud services explain themselves when this build has none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: AccountViewModel = viewModel { AccountViewModel(container) }
    val account by vm.account.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) { vm.refreshOnOpen() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(context.getString(it)) } }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.gutterCompact)
                    .padding(bottom = Dimens.space12),
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                val acc = account
                when {
                    !container.auth.available -> EmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = stringResource(R.string.cloud_off_title),
                        body = stringResource(R.string.cloud_off_body),
                    )
                    acc == null -> SignedOut(vm, busy)
                    else -> {
                        SignedIn(vm, acc, busy)
                        BackupSection(acc) { snackbar.showSnackbar(context.getString(it)) }
                        SubscriptionSection(acc) { snackbar.showSnackbar(context.getString(it)) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedOut(vm: AccountViewModel, busy: Boolean) {
    val context = LocalContext.current
    var create by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    Text(stringResource(R.string.account_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false to R.string.sign_in, true to R.string.create_account).forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = create == value,
                onClick = { create = value },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (create) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(80) }, singleLine = true,
            label = { Text(stringResource(R.string.your_name)) }, modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = email, onValueChange = { email = it.trim().take(254) }, singleLine = true,
        label = { Text(stringResource(R.string.email)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it.take(128) }, singleLine = true,
        label = { Text(stringResource(R.string.password)) },
        supportingText = { if (create) Text(stringResource(R.string.password_rule)) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (show) R.string.hide_password else R.string.show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    val valid = email.contains('@') && password.length >= 8 && (!create || name.isNotBlank())
    ScannerButton(
        text = stringResource(if (create) R.string.create_account else R.string.sign_in),
        onClick = {
            if (create) vm.run(R.string.verify_sent) { vm.container.auth.signUp(name, email, password) }
            else vm.run { vm.container.auth.signIn(email, password) }
        },
        enabled = valid,
        loading = busy,
        size = ButtonSize.LG,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!create) {
        ScannerButton(
            text = stringResource(R.string.forgot_password),
            onClick = { vm.run(R.string.reset_sent) { vm.container.auth.sendPasswordReset(email) } },
            enabled = email.contains('@') && !busy,
            variant = ButtonVariant.TEXT,
        )
    }
    if (CloudConfig.googleSignIn) {
        HorizontalDivider(Modifier.padding(vertical = Dimens.space2))
        ScannerButton(
            text = stringResource(R.string.continue_google),
            onClick = { vm.run { vm.container.auth.signInWithGoogle(context) } },
            variant = ButtonVariant.SECONDARY,
            size = ButtonSize.LG,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignedIn(vm: AccountViewModel, account: Account, busy: Boolean) {
    val context = LocalContext.current
    var name by remember(account.uid) { mutableStateOf(account.displayName.orEmpty()) }

    SectionHeader(stringResource(R.string.profile))
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(80) }, singleLine = true,
        label = { Text(stringResource(R.string.your_name)) }, modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ScannerButton(
            text = stringResource(R.string.save),
            onClick = { vm.run(R.string.profile_saved) { vm.container.auth.updateDisplayName(name) } },
            enabled = name.isNotBlank() && name != account.displayName && !busy,
            size = ButtonSize.SM,
        )
    }
    Text(account.email.orEmpty(), style = MaterialTheme.typography.bodyLarge)
    if (account.usesPassword && !account.emailVerified) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.verify_body), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    ScannerButton(
                        stringResource(R.string.verify_resend),
                        { vm.run(R.string.verify_sent) { vm.container.auth.resendVerification() } },
                        variant = ButtonVariant.TEXT, size = ButtonSize.SM, enabled = !busy,
                    )
                    ScannerButton(
                        stringResource(R.string.verify_done),
                        { vm.run { vm.container.auth.refresh() } },
                        size = ButtonSize.SM, enabled = !busy,
                    )
                }
            }
        }
    } else {
        Text(stringResource(R.string.email_verified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
    }
    ScannerButton(
        text = stringResource(R.string.sign_out),
        onClick = { vm.run { runCatching { vm.container.signOut(context) } } },
        variant = ButtonVariant.SECONDARY,
        enabled = !busy,
    )
}
