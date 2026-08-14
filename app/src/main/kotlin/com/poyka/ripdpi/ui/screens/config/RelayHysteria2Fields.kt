package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun HysteriaRelayFields(
    draft: ConfigDraft,
    actions: RelayHysteriaActions,
) {
    RipDpiTextField(
        value = draft.relayHysteriaPassword,
        onValueChange = actions.onRelayHysteriaPasswordChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_hysteria_password)),
    )
    RipDpiTextField(
        value = draft.relayHysteriaSalamanderKey,
        onValueChange = actions.onRelayHysteriaSalamanderKeyChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_hysteria_salamander)),
    )
}
