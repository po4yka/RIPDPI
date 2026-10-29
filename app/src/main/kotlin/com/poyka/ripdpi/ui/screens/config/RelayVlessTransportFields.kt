package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.selectedVlessFlow
import com.poyka.ripdpi.data.NativeVlessCapabilities
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelaySecurityLayerReality
import com.poyka.ripdpi.data.RelaySecurityLayerTls
import com.poyka.ripdpi.data.RelayVlessFlowVision
import com.poyka.ripdpi.data.RelayVlessFlowVisionUdp443
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeAuto
import com.poyka.ripdpi.data.RelayXhttpModeStreamUp
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalLayoutApi::class)
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        NativeVlessCapabilities.transports(draft.vlessSecurity()).forEach { transport ->
            VlessTransportChip(
                selectedTransport = draft.relayVlessTransport,
                transport = transport,
                labelRes =
                    if (transport ==
                        RelayVlessTransportRealityTcp
                    ) {
                        R.string.config_relay_vless_transport_reality_tcp
                    } else {
                        R.string.config_relay_vless_transport_xhttp
                    },
                onRelayVlessTransportChanged = actions.onRelayVlessTransportChanged,
            )
        }
    }
    if (draft.relayKind in setOf(RelayKindVless, RelayKindVlessReality)) {
        VlessFlowFields(draft, actions)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun XhttpFields(
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        NativeVlessCapabilities.xhttpModes(draft.vlessSecurity()).forEach { mode ->
            XhttpModeChip(
                selectedMode = draft.relayXhttpMode,
                mode = mode,
                labelRes =
                    when (mode) {
                        RelayXhttpModeAuto -> R.string.config_relay_xhttp_mode_auto
                        RelayXhttpModeStreamUp -> R.string.config_relay_xhttp_mode_stream_up
                        else -> R.string.config_relay_xhttp_mode_stream_one
                    },
                onRelayXhttpModeChanged = actions.onRelayXhttpModeChanged,
            )
        }
    }
}

@Composable
private fun VlessTransportChip(
    selectedTransport: String,
    transport: String,
    labelRes: Int,
    onRelayVlessTransportChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedTransport == transport,
        onClick = { onRelayVlessTransportChanged(transport) },
    )
}

@Composable
private fun XhttpModeChip(
    selectedMode: String,
    mode: String,
    labelRes: Int,
    onRelayXhttpModeChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedMode == mode,
        onClick = { onRelayXhttpModeChanged(mode) },
    )
}

internal fun ConfigDraft.showsXhttpFields(): Boolean =
    relayKind == RelayKindCloudflareTunnel || relayVlessTransport == RelayVlessTransportXhttp

private fun ConfigDraft.vlessSecurity(): String =
    if (relayKind == RelayKindVless) RelaySecurityLayerTls else RelaySecurityLayerReality

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VlessFlowFields(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    Text(
        text = stringResource(R.string.config_relay_vless_flow),
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    val selectedFlow = draft.selectedVlessFlow().trim().let { if (it in listOf("none", "off")) "" else it }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        NativeVlessCapabilities.flows(draft.vlessSecurity(), draft.relayVlessTransport).forEach { flow ->
            RipDpiChip(
                text =
                    when (flow) {
                        "" -> stringResource(R.string.semantic_state_off)
                        RelayVlessFlowVision -> "Vision"
                        RelayVlessFlowVisionUdp443 -> "Vision + UDP/443"
                        else -> flow
                    },
                selected = selectedFlow == flow,
                onClick = { actions.onRelayVlessFlowChanged(flow) },
            )
        }
    }
}
