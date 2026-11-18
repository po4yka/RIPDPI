package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

// Declaration order is the display order after the recommended lane.
enum class StrategyLaneOutcome { Passed, Partial, Blocked, Skipped }

@Immutable
data class StrategyLaneUiState(
    val label: String,
    val outcome: StrategyLaneOutcome,
    val succeededTargets: Int,
    val totalTargets: Int,
    val latencyMs: Long?,
    val recommended: Boolean,
)

@Immutable
data class StrategyLanesUiState(
    val lanes: ImmutableList<StrategyLaneUiState>,
    val finishedAt: Long,
)

internal const val MaxStrategyLanes = 8

// shortcut: only the newest sessions are searched, raise the limit if the last strategy test falls out of it.
internal const val StrategyLanesSessionLimit = 8

/**
 * Sessions arrive newest first. The newest strategy test wins even when it has no candidates:
 * an older test must not pass for the latest one.
 */
internal fun List<DiagnosticScanSession>.latestStrategyLanes(): StrategyLanesUiState? =
    firstOrNull { it.report?.strategyProbeReport != null }?.let { session ->
        session.report?.strategyProbeReport?.toStrategyLanes(session.finishedAt ?: session.startedAt)
    }

internal fun StrategyProbeReport.toStrategyLanes(finishedAt: Long): StrategyLanesUiState? {
    val lanes =
        (
            tcpCandidates.map { it.toLane(recommendation?.tcpCandidateId) } +
                quicCandidates.map { it.toLane(recommendation?.quicCandidateId) }
        ).sortedWith(
            compareByDescending<StrategyLaneUiState> { it.recommended }
                .thenBy { it.outcome }
                .thenByDescending { it.succeededTargets.toFloat() / it.totalTargets.coerceAtLeast(1) }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE },
        ).take(MaxStrategyLanes)
    return if (lanes.isEmpty()) null else StrategyLanesUiState(lanes.toImmutableList(), finishedAt)
}

private fun StrategyProbeCandidateSummary.toLane(recommendedId: String?): StrategyLaneUiState {
    // The engine's outcome is the verdict; the diagnostics screen reads the same values.
    val laneOutcome =
        when {
            skipped || totalTargets <= 0 || outcome.equals("not_applicable", ignoreCase = true) -> {
                StrategyLaneOutcome.Skipped
            }

            outcome.equals("success", ignoreCase = true) -> {
                StrategyLaneOutcome.Passed
            }

            outcome.equals("partial", ignoreCase = true) -> {
                StrategyLaneOutcome.Partial
            }

            else -> {
                StrategyLaneOutcome.Blocked
            }
        }
    val ran = laneOutcome != StrategyLaneOutcome.Skipped
    return StrategyLaneUiState(
        label = label,
        outcome = laneOutcome,
        succeededTargets = succeededTargets.coerceIn(0, totalTargets.coerceAtLeast(0)),
        totalTargets = totalTargets.coerceAtLeast(0),
        latencyMs = averageLatencyMs.takeIf { ran && succeededTargets > 0 },
        // A recommended candidate that did not run is a fallback, not a winner.
        recommended = ran && id == recommendedId,
    )
}
