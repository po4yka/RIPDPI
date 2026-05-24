package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Default replay target used until typed navigation arguments
 * (`Route.ReplayFailure(domain, strategyId)`) land. Production callers
 * should drive [ReplayFailureViewModel.start] from real diagnostics
 * context — this default exists so the developer-reachable nav entry
 * demonstrates the wiring end-to-end.
 */
private const val DefaultReplayDomain = "www.youtube.com"
private const val DefaultReplayStrategyId = "default"

@Composable
fun ReplayFailureRoute(
    onBack: () -> Unit,
    viewModel: ReplayFailureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Auto-start once per ViewModel instance. The check on
        // timestampLabel.isEmpty() guards against re-entry on
        // configuration changes where the VM (and its in-flight state)
        // is retained but the composition is recreated.
        if (state.timestampLabel.isEmpty()) {
            viewModel.start(
                domain = DefaultReplayDomain,
                strategyId = DefaultReplayStrategyId,
            )
        }
    }

    val recommendation =
        if (state.recommendationKey.isNotEmpty()) {
            resolveRecommendation(state.recommendationKey)
        } else if (state.errorMessage != null) {
            state.errorMessage.orEmpty()
        } else {
            ""
        }

    ReplayFailureScreen(
        timestampLabel = state.timestampLabel,
        probeSummary = state.probeSummary,
        steps = state.steps,
        recommendation = recommendation,
        onReplay = {
            viewModel.start(
                domain = DefaultReplayDomain,
                strategyId = DefaultReplayStrategyId,
            )
        },
        onBack = onBack,
    )
}

/**
 * English-fallback resolver for recommendation keys emitted by
 * [com.poyka.ripdpi.diagnostics.replay.ReplayRecommendationEngine].
 * Real `R.string` lookups land in P4.5 alongside the 7-locale keys;
 * keeping the table in-file means the wiring is testable today without
 * blocking on the translation pass.
 */
private fun resolveRecommendation(key: String): String =
    when (key) {
        "vpn_replay_rec_rst_after_client_hello" -> {
            "Possible RST + SNI inspection · try tlsrec_split_host instead"
        }

        "vpn_replay_rec_dns_tampered" -> {
            "DNS appears tampered · try a DNS-over-HTTPS resolver"
        }

        "vpn_replay_rec_tcp_unreachable" -> {
            "Target TCP port unreachable · check the destination"
        }

        "vpn_replay_rec_handshake_alert" -> {
            "TLS handshake alert · try a different TLS profile"
        }

        "vpn_replay_rec_timeout_no_response" -> {
            "No response within timeout · try a different strategy"
        }

        else -> {
            "Probe failed · try a different strategy"
        }
    }
