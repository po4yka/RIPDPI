package com.poyka.ripdpi.ui.screens.mieru

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayMieruMtuDefault
import com.poyka.ripdpi.data.RelayMieruMtuMax
import com.poyka.ripdpi.data.RelayMieruMtuMin
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruMultiplexingLow
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruMultiplexingOff
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorScaffold
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorStatus
import com.poyka.ripdpi.ui.screens.relayeditor.RelayFieldBinding
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
private fun rememberMieruProtocolDropdownOptions():
    ImmutableList<com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption<String>> =
    MieruProtocolOptions
        .map { value ->
            val label =
                when (value) {
                    RelayMieruProtocolTcp -> stringResource(R.string.mieru_transport_tcp)
                    RelayMieruProtocolUdp -> stringResource(R.string.mieru_transport_udp)
                    else -> value
                }
            RipDpiDropdownOption(value = value, label = label)
        }.toImmutableList()

@Composable
private fun rememberMieruMultiplexingDropdownOptions():
    ImmutableList<com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption<String>> =
    MieruMultiplexingOptions
        .map { value ->
            val label =
                when (value) {
                    RelayMieruMultiplexingOff -> stringResource(R.string.mieru_mux_off)
                    RelayMieruMultiplexingLow -> stringResource(R.string.mieru_mux_low)
                    RelayMieruMultiplexingMiddle -> stringResource(R.string.mieru_mux_middle)
                    RelayMieruMultiplexingHigh -> stringResource(R.string.mieru_mux_high)
                    else -> value
                }
            RipDpiDropdownOption(value = value, label = label)
        }.toImmutableList()

/**
 * Mieru profile editor destination.
 *
 * Mieru is surfaced as an actively developed outbound (no legacy banner). The
 * editor validates the username and password as non-blank, the port against
 * `1..65535`, the MTU against `1280..1500`, restricts the transport protocol to
 * `tcp` / `udp` and the multiplexing to `off` / `low` / `middle` / `high`.
 */
@Composable
fun MieruProfileRoute(
    onBack: () -> Unit,
    profileId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: MieruProfileViewModel = hiltViewModel(),
) {
    LaunchedEffect(profileId) { profileId?.let(viewModel::loadProfile) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(viewModel.savedEvents) { onBack() }
    MieruProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onProtocolSelected = viewModel::onProtocolSelected,
        onMultiplexingSelected = viewModel::onMultiplexingSelected,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
internal fun MieruProfileScreen(
    uiState: MieruProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (MieruEditorField, String) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onMultiplexingSelected: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = uiState.editor
    val fields =
        remember(editor, onFieldChanged) {
            RelayFieldBinding(
                kind = RelayKindMieru,
                invalidRes = R.string.mieru_field_invalid,
                rawText = editor::rawText,
                hasError = editor::hasFieldError,
                onChanged = onFieldChanged,
            )
        }
    RelayEditorScaffold(
        kind = RelayKindMieru,
        route = if (uiState.editing || uiState.notFound) Route.MieruProfileEdit() else Route.MieruProfile,
        title = stringResource(R.string.mieru_editor_title),
        saveLabel = stringResource(R.string.mieru_save_action),
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
        banner = {
            WarningBanner(
                title = stringResource(R.string.mieru_experimental_title),
                message = stringResource(R.string.mieru_experimental_body),
                tone = WarningBannerTone.Warning,
                announce = false,
            )
        },
    ) {
        fields.EndpointCard(
            MieruEditorField.DISPLAY_NAME,
            MieruEditorField.SERVER,
            MieruEditorField.SERVER_PORT,
            R.string.mieru_section_endpoint,
            R.string.mieru_field_display_name,
            R.string.mieru_field_server,
            R.string.mieru_field_server_port,
            uiState.editing,
        )
        RipDpiCard {
            RipDpiPanelHeader(title = stringResource(R.string.mieru_section_credentials))
            fields.Text(MieruEditorField.USERNAME, R.string.mieru_field_username)
            fields.Password(MieruEditorField.PASSWORD, R.string.mieru_field_password)
        }
        TransportSection(editor, fields, onProtocolSelected, onMultiplexingSelected)
    }
}

@Composable
private fun TransportSection(
    editor: MieruProfileEditorState,
    fields: RelayFieldBinding<MieruEditorField>,
    onProtocolSelected: (String) -> Unit,
    onMultiplexingSelected: (String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.mieru_section_transport),
        )
        RipDpiDropdown(
            options = rememberMieruProtocolDropdownOptions(),
            selectedValue = editor.protocol,
            onValueSelected = onProtocolSelected,
            label = stringResource(R.string.mieru_field_protocol),
        )
        RipDpiDropdown(
            options = rememberMieruMultiplexingDropdownOptions(),
            selectedValue = editor.multiplexing,
            onValueSelected = onMultiplexingSelected,
            label = stringResource(R.string.mieru_field_multiplexing),
        )
        fields.Text(
            MieruEditorField.MTU,
            R.string.mieru_field_mtu,
            keyboardType = KeyboardType.Number,
            placeholder = RelayMieruMtuDefault.toString(),
            helperText = stringResource(R.string.relay_editor_range_helper, RelayMieruMtuMin, RelayMieruMtuMax),
        )
    }
}
