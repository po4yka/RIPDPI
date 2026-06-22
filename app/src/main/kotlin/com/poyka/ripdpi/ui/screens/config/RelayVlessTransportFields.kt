package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeAuto
import com.poyka.ripdpi.data.RelayXhttpModeStreamOne
import com.poyka.ripdpi.data.RelayXhttpModeStreamUp
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun VlessTransportFields(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    Text(
        text = stringResource(R.string.config_relay_vless_transport),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        VlessTransportChip(
            selectedTransport = draft.relayVlessTransport,
            transport = RelayVlessTransportRealityTcp,
            labelRes = R.string.config_relay_vless_transport_reality_tcp,
            onRelayVlessTransportChanged = actions.onRelayVlessTransportChanged,
        )
        VlessTransportChip(
            selectedTransport = draft.relayVlessTransport,
            transport = RelayVlessTransportXhttp,
            labelRes = R.string.config_relay_vless_transport_xhttp,
            onRelayVlessTransportChanged = actions.onRelayVlessTransportChanged,
        )
    }
    if (draft.showsXhttpFields()) {
        XhttpFields(draft = draft, actions = actions)
    }
}

@Composable
internal fun VlessUuidField(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    RipDpiTextField(
        value = draft.relayVlessUuid,
        onValueChange = actions.onRelayVlessUuidChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_vless_uuid)),
    )
}

@Composable
private fun XhttpFields(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    RipDpiTextField(
        value = draft.relayXhttpPath,
        onValueChange = actions.onRelayXhttpPathChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_xhttp_path)),
    )
    RipDpiTextField(
        value = draft.relayXhttpHost,
        onValueChange = actions.onRelayXhttpHostChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_xhttp_host)),
    )
    Text(
        text = stringResource(R.string.config_relay_xhttp_mode),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        XhttpModeChip(
            selectedMode = draft.relayXhttpMode,
            mode = RelayXhttpModeAuto,
            labelRes = R.string.config_relay_xhttp_mode_auto,
            onRelayXhttpModeChanged = actions.onRelayXhttpModeChanged,
        )
        XhttpModeChip(
            selectedMode = draft.relayXhttpMode,
            mode = RelayXhttpModeStreamUp,
            labelRes = R.string.config_relay_xhttp_mode_stream_up,
            onRelayXhttpModeChanged = actions.onRelayXhttpModeChanged,
        )
        XhttpModeChip(
            selectedMode = draft.relayXhttpMode,
            mode = RelayXhttpModeStreamOne,
            labelRes = R.string.config_relay_xhttp_mode_stream_one,
            onRelayXhttpModeChanged = actions.onRelayXhttpModeChanged,
        )
    }
}

@Composable
private fun RowScope.VlessTransportChip(
    selectedTransport: String,
    transport: String,
    labelRes: Int,
    onRelayVlessTransportChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedTransport == transport,
        onClick = { onRelayVlessTransportChanged(transport) },
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RowScope.XhttpModeChip(
    selectedMode: String,
    mode: String,
    labelRes: Int,
    onRelayXhttpModeChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedMode == mode,
        onClick = { onRelayXhttpModeChanged(mode) },
        modifier = Modifier.weight(1f),
    )
}

internal fun ConfigDraft.showsXhttpFields(): Boolean =
    relayKind == RelayKindCloudflareTunnel || relayVlessTransport == RelayVlessTransportXhttp
