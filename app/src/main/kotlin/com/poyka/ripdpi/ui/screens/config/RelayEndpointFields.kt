package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayServer
import com.poyka.ripdpi.activities.ConfigFieldRelayServerPort
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun RelayEndpointFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayEndpointActions,
) {
    RipDpiTextField(
        value = draft.relayServer,
        onValueChange = actions.onRelayServerChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_server),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayServer]),
            ),
    )
    RipDpiTextField(
        value = draft.relayServerPort,
        onValueChange = actions.onRelayServerPortChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_server_port),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayServerPort]),
            ),
        behavior = numericTextFieldBehavior(),
    )
    RipDpiTextField(
        value = draft.relayServerName,
        onValueChange = actions.onRelayServerNameChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_server_name)),
    )
}

internal fun numericTextFieldBehavior(): RipDpiTextFieldBehavior =
    RipDpiTextFieldBehavior(
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
