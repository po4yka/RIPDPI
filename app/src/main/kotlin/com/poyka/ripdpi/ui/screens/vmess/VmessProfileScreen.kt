package com.poyka.ripdpi.ui.screens.vmess

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
import com.poyka.ripdpi.data.RelayVmessTransportGrpc
import com.poyka.ripdpi.data.RelayVmessTransportH2
import com.poyka.ripdpi.data.RelayVmessTransportWs
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
 * VMess profile editor destination.
 *
 * VMess is surfaced as a **legacy** outbound (a persistent banner says so). The
 * editor validates the UUID against the RFC-4122 v4 shape, the port against
 * `1..65535`, restricts the cipher to the two AEAD options and the transport to
 * `tcp` / `ws` / `h2` / `grpc`, and exposes the optional transport params only
 * for the matching transport. `alterId` is fixed at `0` and is not editable.
 */
@Composable
fun VmessProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VmessProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    VmessProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onSecuritySelected = viewModel::onSecuritySelected,
        onTransportSelected = viewModel::onTransportSelected,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
internal fun VmessProfileScreen(
    uiState: VmessProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (VmessEditorField, String) -> Unit,
    onSecuritySelected: (String) -> Unit,
    onTransportSelected: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.vmess_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier,
    ) {
        WarningBanner(
            title = stringResource(R.string.vmess_legacy_title),
            message = stringResource(R.string.vmess_legacy_body),
            tone = WarningBannerTone.Warning,
        )
        EndpointSection(uiState.editor, onFieldChanged)
        SecuritySection(uiState.editor, onSecuritySelected, onTransportSelected)
        TransportSection(uiState.editor, onFieldChanged)
        RipDpiButton(
            text = stringResource(R.string.vmess_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.editor.isComplete,
        )
    }
}

@Composable
private fun EndpointSection(
    editor: VmessProfileEditorState,
    onFieldChanged: (VmessEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.vmess_section_endpoint))
        PlainField(VmessEditorField.DISPLAY_NAME, R.string.vmess_field_display_name, editor, onFieldChanged)
        PlainField(VmessEditorField.SERVER, R.string.vmess_field_server, editor, onFieldChanged)
        PlainField(
            VmessEditorField.SERVER_PORT,
            R.string.vmess_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
        PlainField(VmessEditorField.UUID, R.string.vmess_field_uuid, editor, onFieldChanged)
    }
}

@Composable
private fun SecuritySection(
    editor: VmessProfileEditorState,
    onSecuritySelected: (String) -> Unit,
    onTransportSelected: (String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.vmess_section_security),
            supporting = stringResource(R.string.vmess_section_security_body),
        )
        RipDpiDropdown(
            options =
                VmessSecurityOptions
                    .map { RipDpiDropdownOption(value = it, label = it) }
                    .toImmutableList(),
            selectedValue = editor.security,
            onValueSelected = onSecuritySelected,
            label = stringResource(R.string.vmess_field_security),
        )
        RipDpiDropdown(
            options =
                VmessTransportOptions
                    .map { RipDpiDropdownOption(value = it, label = it) }
                    .toImmutableList(),
            selectedValue = editor.transport,
            onValueSelected = onTransportSelected,
            label = stringResource(R.string.vmess_field_transport),
        )
    }
}

@Composable
private fun TransportSection(
    editor: VmessProfileEditorState,
    onFieldChanged: (VmessEditorField, String) -> Unit,
) {
    when (editor.transport) {
        RelayVmessTransportWs -> {
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(R.string.vmess_section_transport_ws))
                PlainField(VmessEditorField.WS_PATH, R.string.vmess_field_ws_path, editor, onFieldChanged)
                PlainField(VmessEditorField.WS_HOST, R.string.vmess_field_ws_host, editor, onFieldChanged)
            }
        }

        RelayVmessTransportH2 -> {
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(R.string.vmess_section_transport_h2))
                PlainField(VmessEditorField.H2_PATH, R.string.vmess_field_h2_path, editor, onFieldChanged)
                PlainField(VmessEditorField.H2_HOST, R.string.vmess_field_h2_host, editor, onFieldChanged)
            }
        }

        RelayVmessTransportGrpc -> {
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(R.string.vmess_section_transport_grpc))
                PlainField(VmessEditorField.GRPC_SERVICE, R.string.vmess_field_grpc_service, editor, onFieldChanged)
            }
        }
    }
}

@Composable
private fun PlainField(
    field: VmessEditorField,
    labelRes: Int,
    editor: VmessProfileEditorState,
    onFieldChanged: (VmessEditorField, String) -> Unit,
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
                errorText = if (hasError) stringResource(R.string.vmess_field_invalid) else null,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}
