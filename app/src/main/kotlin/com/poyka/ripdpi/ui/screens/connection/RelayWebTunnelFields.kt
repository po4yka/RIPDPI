package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.relayeditor.RelayUrlField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayValueField

@Composable
internal fun WebTunnelRelayFields(
    draft: ConnectionDraft,
    actions: RelayMiscKindActions,
) {
    RelayUrlField(
        field = RelayValueField(label = stringResource(R.string.relay_webtunnel_url_label)),
        value = draft.relayWebTunnelUrl,
        onValueChange = actions.onRelayWebTunnelUrlChanged,
    )
}
