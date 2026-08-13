package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState

@Composable
internal fun VlessRealityRelayFields(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    RealityTlsFields(draft = draft, actions = actions)
    VlessTransportFields(draft = draft, actions = actions)
    VlessUuidField(draft = draft, actions = actions)
}

@Composable
private fun RealityTlsFields(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayRealityPublicKey,
                onValueChange = actions.onRelayRealityPublicKeyChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_reality_public_key)),
    )
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayRealityShortId,
                onValueChange = actions.onRelayRealityShortIdChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_reality_short_id)),
    )
}
