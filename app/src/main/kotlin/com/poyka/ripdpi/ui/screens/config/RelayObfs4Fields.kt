package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun Obfs4RelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiConfigTextField(
        value = draft.relayPtBridgeLine,
        onValueChange = actions.onRelayPtBridgeLineChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_tor_bridge_line_label),
                helperText = stringResource(R.string.config_relay_obfs4_bridge_line_helper),
            ),
    )
}
