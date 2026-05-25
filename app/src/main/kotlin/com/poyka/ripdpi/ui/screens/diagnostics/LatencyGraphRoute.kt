package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

/**
 * Default-state entry point for [LatencyGraphScreen]. Production
 * callers should pass a real [LatencyGraphState] computed from live
 * tunnel metrics; this default exists so the developer-reachable nav
 * entry demonstrates the wiring end-to-end.
 */
@Composable
fun LatencyGraphRoute() {
    val state =
        sampleLatencyGraphState(
            title = stringResource(R.string.vpn_latency_graph_title),
            p50Label = stringResource(R.string.vpn_latency_graph_p50_default),
            p95Label = stringResource(R.string.vpn_latency_graph_p95_default),
            nowLabel = stringResource(R.string.vpn_latency_graph_now_default),
            p99Label = stringResource(R.string.vpn_latency_graph_p99_default),
            spikeCountLabel = stringResource(R.string.vpn_latency_graph_spikes_default),
            packetLossLabel = stringResource(R.string.vpn_latency_graph_loss_default),
        )
    LatencyGraphScreen(state = state)
}
