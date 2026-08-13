package com.poyka.ripdpi.ui.screens.mieru

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
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruMultiplexingLow
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruMultiplexingOff
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
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
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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
    modifier: Modifier = Modifier,
    viewModel: MieruProfileViewModel = hiltViewModel(),
) {
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
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.mieru_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.MieruProfile)),
    ) {
        WarningBanner(
            title = stringResource(R.string.mieru_experimental_title),
            message = stringResource(R.string.mieru_experimental_body),
            tone = WarningBannerTone.Warning,
            announce = false,
        )
        uiState.errorMessage?.let { errorRes ->
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(errorRes))
            }
        }
        EndpointSection(uiState.editor, onFieldChanged)
        CredentialsSection(uiState.editor, onFieldChanged)
        TransportSection(uiState.editor, onFieldChanged, onProtocolSelected, onMultiplexingSelected)
        RipDpiButton(
            text = stringResource(R.string.mieru_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.editor.isComplete && !uiState.saving,
        )
    }
}

@Composable
private fun EndpointSection(
    editor: MieruProfileEditorState,
    onFieldChanged: (MieruEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.mieru_section_endpoint))
        PlainField(MieruEditorField.DISPLAY_NAME, R.string.mieru_field_display_name, editor, onFieldChanged)
        PlainField(MieruEditorField.SERVER, R.string.mieru_field_server, editor, onFieldChanged)
        PlainField(
            MieruEditorField.SERVER_PORT,
            R.string.mieru_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
    }
}

@Composable
private fun CredentialsSection(
    editor: MieruProfileEditorState,
    onFieldChanged: (MieruEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.mieru_section_credentials))
        PlainField(MieruEditorField.USERNAME, R.string.mieru_field_username, editor, onFieldChanged)
        PlainField(
            MieruEditorField.PASSWORD,
            R.string.mieru_field_password,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Password,
        )
    }
}

@Composable
private fun TransportSection(
    editor: MieruProfileEditorState,
    onFieldChanged: (MieruEditorField, String) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onMultiplexingSelected: (String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.mieru_section_transport),
            supporting = stringResource(R.string.mieru_section_transport_body),
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
        PlainField(
            MieruEditorField.MTU,
            R.string.mieru_field_mtu,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
    }
}

@Composable
private fun PlainField(
    field: MieruEditorField,
    labelRes: Int,
    editor: MieruProfileEditorState,
    onFieldChanged: (MieruEditorField, String) -> Unit,
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
                errorText = if (hasError) stringResource(R.string.mieru_field_invalid) else null,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}
