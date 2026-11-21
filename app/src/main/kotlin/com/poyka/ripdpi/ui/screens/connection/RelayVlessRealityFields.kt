package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun VlessRealityRelayFields(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
    errors: Map<String, String> = emptyMap(),
) {
    RealityTlsFields(draft = draft, actions = actions, errors = errors)
    VlessTransportFields(draft = draft, actions = actions, errors = errors)
    VlessUuidField(draft = draft, actions = actions, errors = errors)
}

@Composable
private fun RealityTlsFields(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
    errors: Map<String, String>,
) {
    RipDpiTextField(
        value = draft.relayRealityPublicKey,
        onValueChange = actions.onRelayRealityPublicKeyChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_reality_public_key),
                errorText = validationMessage(errors[ConnectionFieldRelayRealityPublicKey]),
            ),
    )
    RipDpiTextField(
        value = draft.relayRealityShortId,
        onValueChange = actions.onRelayRealityShortIdChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_reality_short_id),
                errorText = validationMessage(errors[ConnectionFieldRelayRealityShortId]),
            ),
    )
}
