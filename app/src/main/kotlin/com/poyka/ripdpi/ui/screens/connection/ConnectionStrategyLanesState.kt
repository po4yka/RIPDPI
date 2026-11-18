package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce
import javax.inject.Inject

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
    /** Candidates of the test that the lanes leave out. */
    val hiddenCount: Int = 0,
    /** The device is now on another network than the one the test ran on. */
    val otherNetwork: Boolean = false,
)

internal const val MaxStrategyLanes = 8
internal const val StrategyLanesStopTimeoutMillis = 5_000L

/**
 * The last strategy test as lanes, marked when the device is on another network than the test ran on.
 * "Another network" is the app's own network scope, the key that remembered strategies use: a new DNS
 * server or a change of cellular generation on the same carrier is another scope, and marks the lanes too.
 */
class ConnectionStrategyLanesSource
    @Inject
    constructor(
        private val history: DiagnosticsHistorySource,
        private val fingerprints: NetworkFingerprintProvider,
        private val handovers: NetworkHandoverMonitor,
        private val dispatchers: AppCoroutineDispatchers,
    ) {
        fun observe(): Flow<StrategyLanesUiState?> =
            combine(history.observeLatestStrategyTest(), currentNetworkScopeKey()) { session, network ->
                session?.toStrategyLanes(currentNetworkScopeKey = network)
            }.distinctUntilChanged()

        private fun currentNetworkScopeKey(): Flow<String?> =
            handovers
                .observe()
                .map { handover -> handover.currentFingerprint?.scopeKey() }
                .onStart { emit(runCatching { fingerprints.capture() }.getOrNull()?.scopeKey()) }
                // A moment without a network keeps the last known one, so the mark does not flicker.
                .runningReduce { last, next -> next ?: last }
                .flowOn(dispatchers.io)
    }

/**
 * A test with no candidates gives no lanes. A network counts as another one only when both keys are known:
 * an old row, or a moment without a network, must not mark the lanes.
 */
internal fun DiagnosticScanSession.toStrategyLanes(currentNetworkScopeKey: String?): StrategyLanesUiState? =
    report?.strategyProbeReport?.toStrategyLanes(finishedAt ?: startedAt)?.copy(
        otherNetwork =
            networkScopeKey != null && currentNetworkScopeKey != null && networkScopeKey != currentNetworkScopeKey,
    )

internal fun StrategyProbeReport.toStrategyLanes(finishedAt: Long): StrategyLanesUiState? {
    val ranked =
        (
            tcpCandidates.map { it.toLane(recommendation?.tcpCandidateId) } +
                quicCandidates.map { it.toLane(recommendation?.quicCandidateId) }
        ).sortedWith(
            compareByDescending<StrategyLaneUiState> { it.recommended }
                .thenBy { it.outcome }
                .thenByDescending { it.succeededTargets.toFloat() / it.totalTargets.coerceAtLeast(1) }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE },
        )
    if (ranked.isEmpty()) return null
    return StrategyLanesUiState(
        lanes = ranked.take(MaxStrategyLanes).toImmutableList(),
        finishedAt = finishedAt,
        hiddenCount = (ranked.size - MaxStrategyLanes).coerceAtLeast(0),
    )
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
