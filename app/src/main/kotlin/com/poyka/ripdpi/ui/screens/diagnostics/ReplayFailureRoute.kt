package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals

/**
 * Default replay target for preview/test callers. Production callers should drive
 * [ReplayFailureViewModel.start] from real diagnostics context.
 */
private const val DefaultReplayDomain = "www.youtube.com"
private const val DefaultReplayStrategyId = "default"

@Composable
fun ReplayFailureRoute(
    domain: String = DefaultReplayDomain,
    strategyId: String = DefaultReplayStrategyId,
    sessionId: String? = null,
    onBack: () -> Unit,
    viewModel: ReplayFailureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(domain, strategyId, sessionId) {
        // Auto-start the probe for this target session. ensureStarted keys
        // the dedup on the retained request rather than on UI state, so a
        // configuration change (new composition, same ViewModel and target)
        // stays a no-op while a changed (domain, strategyId) key restarts.
        viewModel.ensureStarted(
            domain = domain,
            strategyId = strategyId,
            sessionId = sessionId,
        )
    }

    val recommendation =
        if (state.recommendationKey.isNotEmpty()) {
            resolveRecommendation(state.recommendationKey)
        } else if (state.errorMessage != null) {
            state.errorMessage.orEmpty()
        } else {
            ""
        }

    if (state.confirmationRequired) {
        RipDpiDialog(
            onDismissRequest = viewModel::dismissDisruption,
            title = stringResource(R.string.vpn_replay_confirm_title),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.config_cancel),
                    onClick = viewModel::dismissDisruption,
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.vpn_replay_failure_replay),
                    onClick = viewModel::confirmDisruption,
                ),
            visuals = RipDpiDialogVisuals(message = stringResource(R.string.vpn_replay_confirm_message)),
        )
    }

    ReplayFailureScreen(
        timestampLabel = state.timestampLabel,
        probeSummary = state.probeSummary,
        steps = state.steps,
        recommendation = recommendation,
        isRunning = state.isRunning,
        onReplay = {
            viewModel.requestStart(
                domain = domain,
                strategyId = strategyId,
                sessionId = sessionId,
            )
        },
        onBack = onBack,
    )
}

/**
 * Resolves recommendation keys emitted by
 * [com.poyka.ripdpi.diagnostics.replay.ReplayRecommendationEngine]
 * to localized strings backed by `R.string.vpn_replay_rec_*` across
 * all 7 supported locales. Unknown keys fall back to the generic
 * "probe failed" message.
 */
@Composable
private fun resolveRecommendation(key: String): String =
    when (key) {
        "vpn_replay_rec_rst_after_client_hello" -> {
            stringResource(R.string.vpn_replay_rec_rst_after_client_hello)
        }

        "vpn_replay_rec_dns_tampered" -> {
            stringResource(R.string.vpn_replay_rec_dns_tampered)
        }

        "vpn_replay_rec_tcp_unreachable" -> {
            stringResource(R.string.vpn_replay_rec_tcp_unreachable)
        }

        "vpn_replay_rec_handshake_alert" -> {
            stringResource(R.string.vpn_replay_rec_handshake_alert)
        }

        "vpn_replay_rec_timeout_no_response" -> {
            stringResource(R.string.vpn_replay_rec_timeout_no_response)
        }

        else -> {
            stringResource(R.string.vpn_replay_rec_generic_fallback)
        }
    }
