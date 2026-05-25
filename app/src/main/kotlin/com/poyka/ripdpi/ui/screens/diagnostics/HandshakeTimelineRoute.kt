package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

/**
 * Default-state entry point for [HandshakeTimelineScreen]. Production
 * callers should pass a real [HandshakeTimelineState] computed from
 * handshake events; this default exists so the developer-reachable nav
 * entry demonstrates the wiring end-to-end.
 */
@Composable
fun HandshakeTimelineRoute() {
    val state =
        sampleHandshakeTimelineState(
            title = stringResource(R.string.vpn_handshake_timeline_title),
            subtitle = stringResource(R.string.vpn_handshake_timeline_subtitle_default),
            totalLabel = stringResource(R.string.vpn_handshake_timeline_first_packet_label),
            footerSlowest = stringResource(R.string.vpn_handshake_timeline_slowest_default),
            footerBudget = stringResource(R.string.vpn_handshake_timeline_next_budget_default),
        )
    HandshakeTimelineScreen(state = state)
}
