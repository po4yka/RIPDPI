package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

/**
 * Default-state entry point for [ThroughputGraphScreen]. Production
 * callers should pass a real [ThroughputGraphState] computed from live
 * tunnel metrics; this default exists so the developer-reachable nav
 * entry demonstrates the wiring end-to-end.
 */
@Composable
fun ThroughputGraphRoute() {
    val state =
        sampleThroughputGraphState(
            title = stringResource(R.string.vpn_throughput_graph_title),
            downNowLabel = stringResource(R.string.vpn_throughput_graph_down_now_default),
            upNowLabel = stringResource(R.string.vpn_throughput_graph_up_now_default),
            sessionTotalLabel = stringResource(R.string.vpn_throughput_graph_session_default),
        )
    ThroughputGraphScreen(state = state)
}
