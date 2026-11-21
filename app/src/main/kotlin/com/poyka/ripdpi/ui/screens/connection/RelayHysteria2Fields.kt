package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.relayeditor.RelayPasswordField

@Composable
internal fun HysteriaRelayFields(
    draft: ConnectionDraft,
    actions: RelayHysteriaActions,
    errors: Map<String, String> = emptyMap(),
) {
    RelayPasswordField(
        label = stringResource(R.string.config_relay_hysteria_password),
        value = draft.relayHysteriaPassword,
        onValueChange = actions.onRelayHysteriaPasswordChanged,
        errorText = validationMessage(errors[ConnectionFieldRelayPassword]),
    )
    RelayPasswordField(
        label = stringResource(R.string.config_relay_hysteria_salamander),
        value = draft.relayHysteriaSalamanderKey,
        onValueChange = actions.onRelayHysteriaSalamanderKeyChanged,
    )
}
