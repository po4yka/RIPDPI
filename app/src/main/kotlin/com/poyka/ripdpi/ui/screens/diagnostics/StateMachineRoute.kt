package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

/**
 * Default-state entry point for [StateMachineScreen]. Production
 * callers should pass a real [StateMachineState] derived from live
 * VPN engine events; this default exists so the developer-reachable nav
 * entry demonstrates the wiring end-to-end.
 */
@Composable
fun StateMachineRoute() {
    val state =
        sampleStateMachineState(
            currentStateLabel = stringResource(R.string.vpn_state_machine_node_tunneling),
            transitionCountLabel = stringResource(R.string.vpn_state_machine_transitions_default),
            disconnectedMetaLabel = stringResource(R.string.vpn_state_machine_meta_idle),
            permissioningMetaLabel = stringResource(R.string.vpn_state_machine_meta_os_prompt),
            connectingMetaLabel = stringResource(R.string.vpn_state_machine_meta_handshake),
            tunnelingMetaLabel = stringResource(R.string.vpn_state_machine_meta_tunneling_default),
            reconnectingMetaLabel = stringResource(R.string.vpn_state_machine_meta_backoff),
            failedMetaLabel = stringResource(R.string.vpn_state_machine_meta_failed_default),
            degradedMetaLabel = stringResource(R.string.vpn_state_machine_meta_degraded_default),
        )
    StateMachineScreen(state = state)
}
