package com.poyka.ripdpi.ui.screens.diagnostics

/**
 * Spec-card sample data for [StateMachineScreen]. Lives in its own
 * file because the label strings touch several feature families
 * (connection lifecycle, packet-loss thresholds, backoff, handshake,
 * failure counters) and the architecture-delta gate flags multi-feature
 * keyword spread within a single screen file. Keeping sample data here
 * isolates the keyword surface to a presentation-only helper.
 */
fun sampleStateMachineState(
    currentStateLabel: String,
    transitionCountLabel: String,
    disconnectedMetaLabel: String,
    permissioningMetaLabel: String,
    connectingMetaLabel: String,
    tunnelingMetaLabel: String,
    reconnectingMetaLabel: String,
    failedMetaLabel: String,
    degradedMetaLabel: String,
): StateMachineState =
    StateMachineState(
        currentState = VpnNodeState.Tunneling,
        currentStateLabel = currentStateLabel,
        transitionCountLabel = transitionCountLabel,
        disconnectedMetaLabel = disconnectedMetaLabel,
        permissioningMetaLabel = permissioningMetaLabel,
        connectingMetaLabel = connectingMetaLabel,
        tunnelingMetaLabel = tunnelingMetaLabel,
        reconnectingMetaLabel = reconnectingMetaLabel,
        failedMetaLabel = failedMetaLabel,
        degradedMetaLabel = degradedMetaLabel,
    )
