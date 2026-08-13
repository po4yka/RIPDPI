package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState

@Composable
internal fun WebTunnelRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayWebTunnelUrl,
                onValueChange = actions.onRelayWebTunnelUrlChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.relay_webtunnel_url_label)),
    )
}
