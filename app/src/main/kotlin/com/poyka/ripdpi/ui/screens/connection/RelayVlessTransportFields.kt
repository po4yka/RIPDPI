package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
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
import com.poyka.ripdpi.data.RelayXhttpModePacketUp
import com.poyka.ripdpi.data.RelayXhttpModeStreamUp
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.screens.relayeditor.RelayPasswordField
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VlessTransportFields(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
    errors: Map<String, String> = emptyMap(),
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
    RelayChoiceError(errors[ConnectionFieldRelayVlessTransport])
    if (draft.relayKind in setOf(RelayKindVless, RelayKindVlessReality)) {
        VlessFlowFields(draft, actions, errors)
    }
    if (draft.showsXhttpFields()) {
        XhttpFields(draft = draft, actions = actions, errors = errors)
    }
}

@Composable
internal fun VlessUuidField(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
    errors: Map<String, String> = emptyMap(),
) {
    RelayPasswordField(
        label = stringResource(R.string.config_relay_vless_uuid),
        value = draft.relayVlessUuid,
        onValueChange = actions.onRelayVlessUuidChanged,
        errorText = validationMessage(errors[ConnectionFieldRelayVlessUuid]),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun XhttpFields(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
    errors: Map<String, String> = emptyMap(),
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
                        RelayXhttpModePacketUp -> R.string.config_relay_xhttp_mode_packet_up
                        RelayXhttpModeStreamUp -> R.string.config_relay_xhttp_mode_stream_up
                        else -> R.string.config_relay_xhttp_mode_stream_one
                    },
                onRelayXhttpModeChanged = actions.onRelayXhttpModeChanged,
            )
        }
    }
    RelayChoiceError(errors[ConnectionFieldRelayXhttpMode])
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

internal fun ConnectionDraft.showsXhttpFields(): Boolean =
    relayKind == RelayKindCloudflareTunnel || relayVlessTransport == RelayVlessTransportXhttp

private fun ConnectionDraft.vlessSecurity(): String =
    if (relayKind == RelayKindVless) RelaySecurityLayerTls else RelaySecurityLayerReality

/**
 * After a kind, transport, or flow change, moves each dependent VLESS choice that the native backend no longer
 * accepts to its default, so the pickers show the value that will be saved.
 */
internal fun ConnectionDraft.withValidVlessChoices(): ConnectionDraft {
    if (relayKind != RelayKindVless && relayKind != RelayKindVlessReality) return this
    val security = vlessSecurity()
    val transports = NativeVlessCapabilities.transports(security)
    val transport = relayVlessTransport.takeIf { it in transports } ?: transports.first()
    val flow = selectedVlessFlow().trim().let { if (it in listOf("none", "off")) "" else it }
    val validFlow =
        flow.takeIf { it in NativeVlessCapabilities.flows(security, transport) }
            ?: NativeVlessCapabilities.defaultFlow(security, transport)
    val modeValid = relayXhttpMode.trim().ifEmpty { RelayXhttpModeAuto } in NativeVlessCapabilities.xhttpModes(security)
    return copy(
        relayVlessTransport = transport,
        relayVlessFlow = if (validFlow == flow) relayVlessFlow else validFlow,
        relayXhttpMode = if (modeValid) relayXhttpMode else RelayXhttpModeAuto,
        relayUdpEnabled = relayUdpEnabled && NativeVlessCapabilities.supportsUdp(security, transport, validFlow),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VlessFlowFields(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
    errors: Map<String, String> = emptyMap(),
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
    RelayChoiceError(errors[ConnectionFieldRelayVlessFlow])
}

/** The error under a row of choice chips, where a text field would show it. */
@Composable
internal fun RelayChoiceError(errorKey: String?) {
    validationMessage(errorKey)?.let {
        Text(
            text = it,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.destructive,
        )
    }
}
