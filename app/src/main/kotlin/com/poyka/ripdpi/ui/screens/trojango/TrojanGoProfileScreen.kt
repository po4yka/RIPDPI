package com.poyka.ripdpi.ui.screens.trojango

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
 * Trojan-Go profile editor destination.
 *
 * Trojan-Go is surfaced as a **legacy** outbound (a persistent banner says so).
 * The editor validates the password as non-blank, the port against `1..65535`,
 * restricts the SMUX mode to `off` / `smux_v1` and the optional inner cipher to
 * `none` / `aes-256-gcm` / `chacha20-ietf-poly1305`, and exposes the optional
 * SNI and WebSocket transport params.
 */
@Composable
fun TrojanGoProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrojanGoProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TrojanGoProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onMuxSelected = viewModel::onMuxSelected,
        onInnerCipherSelected = viewModel::onInnerCipherSelected,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
internal fun TrojanGoProfileScreen(
    uiState: TrojanGoProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (TrojanGoEditorField, String) -> Unit,
    onMuxSelected: (String) -> Unit,
    onInnerCipherSelected: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.trojan_go_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier,
    ) {
        WarningBanner(
            title = stringResource(R.string.trojan_go_legacy_title),
            message = stringResource(R.string.trojan_go_legacy_body),
            tone = WarningBannerTone.Warning,
        )
        EndpointSection(uiState.editor, onFieldChanged)
        TransportSection(uiState.editor, onFieldChanged)
        MultiplexSection(uiState.editor, onMuxSelected, onInnerCipherSelected)
        RipDpiButton(
            text = stringResource(R.string.trojan_go_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.editor.isComplete,
        )
    }
}

@Composable
private fun EndpointSection(
    editor: TrojanGoProfileEditorState,
    onFieldChanged: (TrojanGoEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.trojan_go_section_endpoint))
        PlainField(TrojanGoEditorField.DISPLAY_NAME, R.string.trojan_go_field_display_name, editor, onFieldChanged)
        PlainField(TrojanGoEditorField.SERVER, R.string.trojan_go_field_server, editor, onFieldChanged)
        PlainField(
            TrojanGoEditorField.SERVER_PORT,
            R.string.trojan_go_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
        PlainField(
            TrojanGoEditorField.PASSWORD,
            R.string.trojan_go_field_password,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Password,
        )
    }
}

@Composable
private fun TransportSection(
    editor: TrojanGoProfileEditorState,
    onFieldChanged: (TrojanGoEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.trojan_go_section_transport),
            supporting = stringResource(R.string.trojan_go_section_transport_body),
        )
        PlainField(TrojanGoEditorField.SNI, R.string.trojan_go_field_sni, editor, onFieldChanged)
        PlainField(TrojanGoEditorField.WS_PATH, R.string.trojan_go_field_ws_path, editor, onFieldChanged)
        PlainField(TrojanGoEditorField.WS_HOST, R.string.trojan_go_field_ws_host, editor, onFieldChanged)
    }
}

@Composable
private fun MultiplexSection(
    editor: TrojanGoProfileEditorState,
    onMuxSelected: (String) -> Unit,
    onInnerCipherSelected: (String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.trojan_go_section_multiplex),
            supporting = stringResource(R.string.trojan_go_section_multiplex_body),
        )
        RipDpiDropdown(
            options =
                TrojanGoMuxOptions
                    .map { RipDpiDropdownOption(value = it, label = it) }
                    .toImmutableList(),
            selectedValue = editor.mux,
            onValueSelected = onMuxSelected,
            label = stringResource(R.string.trojan_go_field_mux),
        )
        RipDpiDropdown(
            options =
                TrojanGoInnerCipherOptions
                    .map { RipDpiDropdownOption(value = it, label = it) }
                    .toImmutableList(),
            selectedValue = editor.innerCipher,
            onValueSelected = onInnerCipherSelected,
            label = stringResource(R.string.trojan_go_field_inner_cipher),
        )
    }
}

@Composable
private fun PlainField(
    field: TrojanGoEditorField,
    labelRes: Int,
    editor: TrojanGoProfileEditorState,
    onFieldChanged: (TrojanGoEditorField, String) -> Unit,
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
                errorText = if (hasError) stringResource(R.string.trojan_go_field_invalid) else null,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}
