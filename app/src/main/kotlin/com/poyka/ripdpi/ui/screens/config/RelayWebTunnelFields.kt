package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun WebTunnelRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        value = draft.relayWebTunnelUrl,
        onValueChange = actions.onRelayWebTunnelUrlChanged,
        decoration = RipDpiTextFieldDecoration(label = "WebTunnel URL"),
    )
}
