package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.relayeditor.RelayHostField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayPortField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayValueField

@Composable
internal fun RelayEndpointFields(
    draft: ConnectionDraft,
    uiState: ConnectionUiState,
    actions: RelayEndpointActions,
) {
    RelayHostField(
        field =
            RelayValueField(
                label = stringResource(R.string.config_relay_server),
                errorText = validationMessage(uiState.validationErrors[ConnectionFieldRelayServer]),
            ),
        value = draft.relayServer,
        onValueChange = actions.onRelayServerChanged,
    )
    RelayPortField(
        field =
            RelayValueField(
                label = stringResource(R.string.config_relay_server_port),
                errorText = validationMessage(uiState.validationErrors[ConnectionFieldRelayServerPort]),
            ),
        value = draft.relayServerPort,
        onValueChange = actions.onRelayServerPortChanged,
    )
    RelayHostField(
        field =
            RelayValueField(
                label = stringResource(R.string.config_relay_server_name),
                errorText = validationMessage(uiState.validationErrors[ConnectionFieldRelayServerName]),
            ),
        value = draft.relayServerName,
        onValueChange = actions.onRelayServerNameChanged,
    )
}
