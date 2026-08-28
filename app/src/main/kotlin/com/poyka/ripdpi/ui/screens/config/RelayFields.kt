package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip

/**
 * Relay kind and finalmask configuration fields for the Mode Editor.
 *
 * All finalmask parameters from [com.poyka.ripdpi.data.ResolvedRelayFinalmaskConfig]
 * are intentionally exposed as explicit typed UI fields:
 * - [RelayFinalmaskTypeHeaderCustom]: header_hex, trailer_hex, rand_range
 * - [RelayFinalmaskTypeSudoku]: sudoku_seed
 * - [RelayFinalmaskTypeFragment]: fragment_packets, fragment_min_bytes, fragment_max_bytes
 * - [RelayFinalmaskTypeNoise]: rand_range
 *
 * The `chain_dsl` text-field in the Engine section is a separate DPI bypass strategy DSL
 * and does NOT accept finalmask parameters. See docs/architecture/README.md#desync-and-relay-rules.
 */
@Composable
internal fun RelayKindFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayKindFieldActions = RelayKindFieldActions(),
) {
    RelayFieldsContent(draft = draft, uiState = uiState, actions = actions)
}

@Composable
internal fun RelayFieldsContent(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayKindFieldActions = RelayKindFieldActions(),
) {
    if (draft.relayKind.supportsStandardRelayEndpointFields()) {
        RelayEndpointFields(draft = draft, uiState = uiState, actions = actions.endpoint)
    }
    when (draft.relayKind) {
        RelayKindVlessReality -> {
            VlessRealityRelayFields(draft = draft, actions = actions.vless)
        }

        RelayKindCloudflareTunnel -> {
            CloudflareTunnelRelayFields(
                draft = draft,
                uiState = uiState,
                actions = actions.vless,
            )
        }

        RelayKindAnyTls -> {
            AnyTlsRelayFields(draft, actions.onAnyTlsPasswordChanged)
        }

        RelayKindHysteria2 -> {
            HysteriaRelayFields(draft = draft, actions = actions.hysteria)
        }

        RelayKindTuicV5 -> {
            TuicRelayFields(draft = draft, actions = actions.tuic)
        }

        RelayKindChainRelay -> {
            RelayChainFields(
                draft = draft,
                uiState = uiState,
                onRelayChainHopProfileIdChanged = actions.chain.onRelayChainHopProfileIdChanged,
                onRelayChainHopAdded = actions.chain.onRelayChainHopAdded,
                onRelayChainHopRemoved = actions.chain.onRelayChainHopRemoved,
                onRelayChainHopMoved = actions.chain.onRelayChainHopMoved,
            )
        }

        RelayKindMasque -> {
            MasqueRelayFields(draft = draft, uiState = uiState, actions = actions.masque)
        }

        RelayKindShadowTlsV3 -> {
            ShadowTlsRelayFields(draft = draft, actions = actions.misc)
        }

        RelayKindNaiveProxy -> {
            NaiveProxyRelayFields(draft = draft, uiState = uiState, actions = actions.misc)
        }

        RelayKindSnowflake -> {
            SnowflakeRelayFields(draft = draft, actions = actions.misc)
        }

        RelayKindWebTunnel -> {
            WebTunnelRelayFields(draft = draft, actions = actions.misc)
        }

        RelayKindObfs4 -> {
            Obfs4RelayFields(draft = draft, actions = actions.misc)
        }

        RelayKindTor -> {
            TorRelayFields(draft = draft, actions = actions.misc)
        }
    }

    RelayFinalmaskFields(draft = draft, uiState = uiState, actions = actions.finalmask)
}

private fun String.supportsStandardRelayEndpointFields(): Boolean =
    this == RelayKindAnyTls ||
        this == RelayKindVlessReality ||
        this == RelayKindCloudflareTunnel ||
        this == RelayKindHysteria2 ||
        this == RelayKindTuicV5 ||
        this == RelayKindNaiveProxy

@Composable
internal fun RowScope.RelayKindChip(
    selectedKind: String,
    kind: String,
    labelRes: Int,
    onRelayKindChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedKind == kind,
        onClick = { onRelayKindChanged(kind) },
        modifier = modifier.weight(1f),
    )
}

@Composable
internal fun RowScope.RelayKindChip(
    selectedKind: String,
    kind: String,
    label: String,
    onRelayKindChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiChip(
        text = label,
        selected = selectedKind == kind,
        onClick = { onRelayKindChanged(kind) },
        modifier = modifier.weight(1f),
    )
}
