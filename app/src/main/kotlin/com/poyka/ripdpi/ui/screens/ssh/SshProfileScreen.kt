package com.poyka.ripdpi.ui.screens.ssh

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.security.AppPinCheck
import com.poyka.ripdpi.security.SecretRevealPinViewModel
import com.poyka.ripdpi.security.rememberSecretRevealGate
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.profiles.RelaySecretField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorScaffold
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorStatus
import com.poyka.ripdpi.ui.screens.relayeditor.RelayFieldBinding
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
private fun rememberSshAuthTypeDropdownOptions():
    ImmutableList<com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption<String>> =
    SshAuthTypeOptions
        .map { value ->
            val label =
                when (value) {
                    RelaySshAuthTypePassword -> stringResource(R.string.ssh_auth_password)
                    RelaySshAuthTypePrivateKey -> stringResource(R.string.ssh_auth_private_key)
                    else -> value
                }
            RipDpiDropdownOption(value = value, label = label)
        }.toImmutableList()

/**
 * SSH profile editor destination.
 *
 * The editor validates the server and username as non-blank and the port against
 * `1..65535`, exposes an auth-type selector (`password` / `private_key`) that
 * drives which credential is required, an optional private-key passphrase, an
 * optional pinned `SHA256:` host-key fingerprint, and a strict-host-key toggle.
 * The private key and the passphrase are secrets gated behind a biometric reveal,
 * mirroring the AmneziaWG private-key reveal. Saving a complete profile applies it
 * as the active native relay and navigates back; the same shape can also arrive via
 * the synthetic `ssh://` URI / subscription import path.
 */
@Composable
fun SshProfileRoute(
    onBack: () -> Unit,
    profileId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: SshProfileViewModel = hiltViewModel(),
    appPin: AppPinCheck? = hiltViewModel<SecretRevealPinViewModel>(),
    onRequestPrivateKeyReveal: (() -> Unit)? = null,
    onRequestPassphraseReveal: (() -> Unit)? = null,
) {
    LaunchedEffect(profileId) { profileId?.let(viewModel::loadProfile) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(viewModel.savedEvents) { onBack() }
    // Revealed secrets hide again when the app goes to the background or the screen closes.
    // A host-key fetch is not cancelled here: rotation would restart it, and the slot the cancelled
    // probe keeps would answer Busy. It runs in viewModelScope, so leaving the screen cancels it.
    LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.onSecretsRelocked() } }
    val revealPrivateKey = rememberSecretRevealGate(viewModel::onPrivateKeyRevealAuthorized, appPin)
    val revealPassphrase = rememberSecretRevealGate(viewModel::onPassphraseRevealAuthorized, appPin)
    SshProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onAuthTypeSelected = viewModel::onAuthTypeSelected,
        onStrictHostKeyChanged = viewModel::onStrictHostKeyChanged,
        onRevealPrivateKey = onRequestPrivateKeyReveal ?: revealPrivateKey,
        onRevealPassphrase = onRequestPassphraseReveal ?: revealPassphrase,
        onSave = viewModel::onSave,
        modifier = modifier,
        onFetchHostKey = viewModel::onFetchHostKey,
        onHostKeyTrustConfirmed = viewModel::onHostKeyTrustConfirmed,
        onHostKeyTrustDismissed = viewModel::onHostKeyTrustDismissed,
    )
}

@Composable
internal fun SshProfileScreen(
    uiState: SshProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (SshEditorField, String) -> Unit,
    onAuthTypeSelected: (String) -> Unit,
    onStrictHostKeyChanged: (Boolean) -> Unit,
    onRevealPrivateKey: () -> Unit,
    onRevealPassphrase: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onFetchHostKey: () -> Unit = {},
    onHostKeyTrustConfirmed: () -> Unit = {},
    onHostKeyTrustDismissed: () -> Unit = {},
) {
    val editor = uiState.editor
    val fields =
        remember(editor, onFieldChanged) {
            RelayFieldBinding(
                kind = RelayKindSsh,
                invalidRes = R.string.ssh_field_invalid,
                rawText = editor::rawText,
                hasError = editor::hasFieldError,
                onChanged = onFieldChanged,
            )
        }
    RelayEditorScaffold(
        kind = RelayKindSsh,
        route = if (uiState.editing || uiState.notFound) Route.SshProfileEdit() else Route.SshProfile,
        title = stringResource(R.string.ssh_editor_title),
        saveLabel = stringResource(R.string.ssh_save_action),
        status =
            RelayEditorStatus(
                complete = editor.isComplete,
                saving = uiState.saving,
                loading = uiState.loading,
                notFound = uiState.notFound,
                errorMessage = uiState.errorMessage,
            ),
        onBack = onBack,
        onSave = onSave,
        modifier = modifier,
    ) {
        fields.EndpointCard(
            SshEditorField.DISPLAY_NAME,
            SshEditorField.SERVER,
            SshEditorField.SERVER_PORT,
            R.string.ssh_section_endpoint,
            R.string.ssh_field_display_name,
            R.string.ssh_field_server,
            R.string.ssh_field_server_port,
            uiState.editing,
        )
        AuthSection(editor, fields, onFieldChanged, onAuthTypeSelected, onRevealPrivateKey, onRevealPassphrase)
        HostKeySection(uiState, fields, onStrictHostKeyChanged, onFetchHostKey)
        (uiState.hostKeyFetch as? SshHostKeyFetchState.PendingConfirmation)?.let { pending ->
            SshHostKeyTrustDialog(pending, onConfirm = onHostKeyTrustConfirmed, onDismiss = onHostKeyTrustDismissed)
        }
    }
}

@Composable
private fun AuthSection(
    editor: SshProfileEditorState,
    fields: RelayFieldBinding<SshEditorField>,
    onFieldChanged: (SshEditorField, String) -> Unit,
    onAuthTypeSelected: (String) -> Unit,
    onRevealPrivateKey: () -> Unit,
    onRevealPassphrase: () -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.ssh_section_auth),
        )
        fields.Text(SshEditorField.USERNAME, R.string.ssh_field_username)
        RipDpiDropdown(
            options = rememberSshAuthTypeDropdownOptions(),
            selectedValue = editor.authType,
            onValueSelected = onAuthTypeSelected,
            label = stringResource(R.string.ssh_field_auth_type),
        )
        if (editor.authType == RelaySshAuthTypePrivateKey) {
            RelaySecretField(
                label = stringResource(R.string.ssh_field_private_key),
                value = editor.rawText(SshEditorField.PRIVATE_KEY),
                revealed = editor.privateKeyRevealed,
                onValueChange = { onFieldChanged(SshEditorField.PRIVATE_KEY, it) },
                onReveal = onRevealPrivateKey,
            )
            RelaySecretField(
                label = stringResource(R.string.ssh_field_private_key_passphrase),
                value = editor.rawText(SshEditorField.PRIVATE_KEY_PASSPHRASE),
                revealed = editor.passphraseRevealed,
                onValueChange = { onFieldChanged(SshEditorField.PRIVATE_KEY_PASSPHRASE, it) },
                onReveal = onRevealPassphrase,
            )
        } else {
            fields.Password(SshEditorField.PASSWORD, R.string.ssh_field_password)
        }
    }
}

@Composable
private fun HostKeySection(
    uiState: SshProfileUiState,
    fields: RelayFieldBinding<SshEditorField>,
    onStrictHostKeyChanged: (Boolean) -> Unit,
    onFetchHostKey: () -> Unit,
) {
    val editor = uiState.editor
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.ssh_section_host_key),
        )
        fields.Text(SshEditorField.HOST_KEY_FINGERPRINT, R.string.ssh_field_host_key_fingerprint)
        SshHostKeyFetchControls(
            state = uiState.hostKeyFetch,
            enabled = uiState.canFetchHostKey && !uiState.loading,
            onFetch = onFetchHostKey,
        )
        RipDpiSwitch(
            checked = editor.strictHostKey,
            onCheckedChange = onStrictHostKeyChanged,
            label = stringResource(R.string.ssh_field_strict_host_key),
            helperText = stringResource(R.string.ssh_field_strict_host_key_body),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
