package com.poyka.ripdpi.ui.screens.ssh

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiSecureTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiSecureTextFieldState
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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
    modifier: Modifier = Modifier,
    viewModel: SshProfileViewModel = hiltViewModel(),
    onRequestPrivateKeyReveal: (() -> Unit)? = null,
    onRequestPassphraseReveal: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(viewModel.savedEvents) { onBack() }
    SshProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onAuthTypeSelected = viewModel::onAuthTypeSelected,
        onStrictHostKeyChanged = viewModel::onStrictHostKeyChanged,
        onRevealPrivateKey = onRequestPrivateKeyReveal ?: viewModel::onPrivateKeyRevealAuthorized,
        onRevealPassphrase = onRequestPassphraseReveal ?: viewModel::onPassphraseRevealAuthorized,
        onSave = viewModel::onSave,
        modifier = modifier,
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
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.ssh_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SshProfile)),
    ) {
        EndpointSection(uiState.editor, onFieldChanged)
        AuthSection(uiState.editor, onFieldChanged, onAuthTypeSelected, onRevealPrivateKey, onRevealPassphrase)
        HostKeySection(uiState.editor, onFieldChanged, onStrictHostKeyChanged)
        RipDpiButton(
            text = stringResource(R.string.ssh_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.editor.isComplete,
        )
    }
}

@Composable
private fun EndpointSection(
    editor: SshProfileEditorState,
    onFieldChanged: (SshEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.ssh_section_endpoint))
        PlainField(SshEditorField.DISPLAY_NAME, R.string.ssh_field_display_name, editor, onFieldChanged)
        PlainField(SshEditorField.SERVER, R.string.ssh_field_server, editor, onFieldChanged)
        PlainField(
            SshEditorField.SERVER_PORT,
            R.string.ssh_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
        PlainField(SshEditorField.USERNAME, R.string.ssh_field_username, editor, onFieldChanged)
    }
}

@Composable
private fun AuthSection(
    editor: SshProfileEditorState,
    onFieldChanged: (SshEditorField, String) -> Unit,
    onAuthTypeSelected: (String) -> Unit,
    onRevealPrivateKey: () -> Unit,
    onRevealPassphrase: () -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.ssh_section_auth),
            supporting = stringResource(R.string.ssh_section_auth_body),
        )
        RipDpiDropdown(
            options = rememberSshAuthTypeDropdownOptions(),
            selectedValue = editor.authType,
            onValueSelected = onAuthTypeSelected,
            label = stringResource(R.string.ssh_field_auth_type),
        )
        if (editor.authType == RelaySshAuthTypePrivateKey) {
            SecretField(
                label = stringResource(R.string.ssh_field_private_key),
                value = editor.rawText(SshEditorField.PRIVATE_KEY),
                revealed = editor.privateKeyRevealed,
                onValueChange = { onFieldChanged(SshEditorField.PRIVATE_KEY, it) },
                onReveal = onRevealPrivateKey,
            )
            SecretField(
                label = stringResource(R.string.ssh_field_private_key_passphrase),
                value = editor.rawText(SshEditorField.PRIVATE_KEY_PASSPHRASE),
                revealed = editor.passphraseRevealed,
                onValueChange = { onFieldChanged(SshEditorField.PRIVATE_KEY_PASSPHRASE, it) },
                onReveal = onRevealPassphrase,
            )
        } else {
            PlainField(
                SshEditorField.PASSWORD,
                R.string.ssh_field_password,
                editor,
                onFieldChanged,
                keyboardType = KeyboardType.Password,
            )
        }
    }
}

@Composable
private fun HostKeySection(
    editor: SshProfileEditorState,
    onFieldChanged: (SshEditorField, String) -> Unit,
    onStrictHostKeyChanged: (Boolean) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.ssh_section_host_key),
            supporting = stringResource(R.string.ssh_section_host_key_body),
        )
        PlainField(
            SshEditorField.HOST_KEY_FINGERPRINT,
            R.string.ssh_field_host_key_fingerprint,
            editor,
            onFieldChanged,
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

@Composable
private fun PlainField(
    field: SshEditorField,
    labelRes: Int,
    editor: SshProfileEditorState,
    onFieldChanged: (SshEditorField, String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val hasError = editor.hasFieldError(field)
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = editor.rawText(field),
                onValueChange = { onFieldChanged(field, it) },
            ),
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(labelRes),
                errorText = if (hasError) stringResource(R.string.ssh_field_invalid) else null,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    revealed: Boolean,
    onValueChange: (String) -> Unit,
    onReveal: () -> Unit,
) {
    RipDpiSecureTextField(
        state =
            rememberRipDpiSecureTextFieldState(
                value = value,
                onValueChange = onValueChange,
            ),
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = label,
                placeholder = if (revealed) null else stringResource(R.string.ssh_secret_hidden),
            ),
        textObfuscationMode =
            if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        trailingContent = {
            RipDpiButton(
                text = stringResource(R.string.ssh_reveal_secret_action),
                onClick = onReveal,
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Visibility,
            )
        },
    )
}
