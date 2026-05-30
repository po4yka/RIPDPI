package com.poyka.ripdpi.ui.screens.hysteriav1

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import kotlinx.collections.immutable.toImmutableList

/**
 * Hysteria v1 profile editor destination.
 *
 * Hysteria v1 is surfaced as a **legacy** outbound (a persistent banner suggests
 * migrating to the active Hysteria2 kind). The editor validates the auth payload
 * as non-blank, the port against `1..65535`, the up/down bandwidth as strictly
 * positive, restricts the auth-type encoding to `string` / `base64` and the
 * transport protocol to `udp` / `wechat-video` / `faketcp`. The obfuscation
 * string, SNI, and ALPN are optional.
 */
@Composable
fun HysteriaV1ProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HysteriaV1ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HysteriaV1ProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onAuthTypeSelected = viewModel::onAuthTypeSelected,
        onProtocolSelected = viewModel::onProtocolSelected,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
internal fun HysteriaV1ProfileScreen(
    uiState: HysteriaV1ProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (HysteriaV1EditorField, String) -> Unit,
    onAuthTypeSelected: (String) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.hysteria_v1_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier,
    ) {
        WarningBanner(
            title = stringResource(R.string.hysteria_v1_legacy_title),
            message = stringResource(R.string.hysteria_v1_legacy_body),
            tone = WarningBannerTone.Warning,
        )
        EndpointSection(uiState.editor, onFieldChanged)
        CredentialsSection(uiState.editor, onFieldChanged, onAuthTypeSelected)
        TransportSection(uiState.editor, onFieldChanged, onProtocolSelected)
        RipDpiButton(
            text = stringResource(R.string.hysteria_v1_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.editor.isComplete,
        )
    }
}

@Composable
private fun EndpointSection(
    editor: HysteriaV1ProfileEditorState,
    onFieldChanged: (HysteriaV1EditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.hysteria_v1_section_endpoint))
        PlainField(HysteriaV1EditorField.DISPLAY_NAME, R.string.hysteria_v1_field_display_name, editor, onFieldChanged)
        PlainField(HysteriaV1EditorField.SERVER, R.string.hysteria_v1_field_server, editor, onFieldChanged)
        PlainField(
            HysteriaV1EditorField.SERVER_PORT,
            R.string.hysteria_v1_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
    }
}

@Composable
private fun CredentialsSection(
    editor: HysteriaV1ProfileEditorState,
    onFieldChanged: (HysteriaV1EditorField, String) -> Unit,
    onAuthTypeSelected: (String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.hysteria_v1_section_credentials))
        RipDpiDropdown(
            options =
                HysteriaV1AuthTypeOptions
                    .map { RipDpiDropdownOption(value = it, label = it) }
                    .toImmutableList(),
            selectedValue = editor.authType,
            onValueSelected = onAuthTypeSelected,
            label = stringResource(R.string.hysteria_v1_field_auth_type),
        )
        PlainField(
            HysteriaV1EditorField.AUTH_PAYLOAD,
            R.string.hysteria_v1_field_auth_payload,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Password,
        )
        PlainField(
            HysteriaV1EditorField.OBFUSCATION,
            R.string.hysteria_v1_field_obfuscation,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Password,
        )
    }
}

@Composable
private fun TransportSection(
    editor: HysteriaV1ProfileEditorState,
    onFieldChanged: (HysteriaV1EditorField, String) -> Unit,
    onProtocolSelected: (String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.hysteria_v1_section_transport),
            supporting = stringResource(R.string.hysteria_v1_section_transport_body),
        )
        RipDpiDropdown(
            options =
                HysteriaV1ProtocolOptions
                    .map { RipDpiDropdownOption(value = it, label = it) }
                    .toImmutableList(),
            selectedValue = editor.protocol,
            onValueSelected = onProtocolSelected,
            label = stringResource(R.string.hysteria_v1_field_protocol),
        )
        PlainField(
            HysteriaV1EditorField.UP_MBPS,
            R.string.hysteria_v1_field_up_mbps,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
        PlainField(
            HysteriaV1EditorField.DOWN_MBPS,
            R.string.hysteria_v1_field_down_mbps,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
        PlainField(HysteriaV1EditorField.SNI, R.string.hysteria_v1_field_sni, editor, onFieldChanged)
        PlainField(HysteriaV1EditorField.ALPN, R.string.hysteria_v1_field_alpn, editor, onFieldChanged)
    }
}

@Composable
private fun PlainField(
    field: HysteriaV1EditorField,
    labelRes: Int,
    editor: HysteriaV1ProfileEditorState,
    onFieldChanged: (HysteriaV1EditorField, String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val hasError = editor.hasFieldError(field)
    RipDpiTextField(
        value = editor.rawText(field),
        onValueChange = { onFieldChanged(field, it) },
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(labelRes),
                errorText = if (hasError) stringResource(R.string.hysteria_v1_field_invalid) else null,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}
