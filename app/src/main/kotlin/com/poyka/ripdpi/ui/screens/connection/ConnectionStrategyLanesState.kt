package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.StrategyCandidateApplier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeLiveProgress
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.ui.screens.diagnostics.state.AutomaticProbingProfileId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

// Declaration order is the display order after the recommended lane.
enum class StrategyLaneOutcome { Passed, Partial, Blocked, Skipped, Running }

/** Where a blocked candidate stopped, in the order a connection passes the stages. */
enum class StrategyLaneStage { Tcp, Tls, Http }

@Immutable
data class StrategyLaneUiState(
    val label: String,
    val outcome: StrategyLaneOutcome,
    val succeededTargets: Int,
    val totalTargets: Int,
    val latencyMs: Long?,
    val recommended: Boolean,
    /** The strategy that was in use when the test ran. */
    val current: Boolean = false,
    /** A family token that names this lane alone in the test; null when the engine label must name it. */
    val family: String? = null,
    /** Candidate id in the test, the key for applying this lane's strategy. */
    val id: String = "",
    /** The stage most of this blocked candidate's sites stopped at; null when the test did not record it. */
    val stage: StrategyLaneStage? = null,
    /** The engine's rules for a winner accept this candidate, so its strategy can be applied. */
    val applicable: Boolean = false,
)

@Immutable
data class StrategyLanesUiState(
    val lanes: ImmutableList<StrategyLaneUiState>,
    val finishedAt: Long,
    /** Candidates of the test that the lanes leave out. */
    val hiddenCount: Int = 0,
    /** The device is now on another network than the one the test ran on. */
    val otherNetwork: Boolean = false,
    /** Set while a scan runs: the lanes are then the candidates tested so far. */
    val progress: StrategyTestProgress? = null,
)

@Immutable
data class StrategyTestProgress(
    val completedSteps: Int,
    val totalSteps: Int,
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
        private val scans: DiagnosticsScanController,
        private val timeline: DiagnosticsTimelineSource,
        private val applier: StrategyCandidateApplier,
    ) {
        /** The running scan while one runs, the last stored strategy test otherwise. */
        fun observe(): Flow<StrategyLanesUiState?> =
            combine(storedTest(), currentNetworkScopeKey(), runningTest()) { (session, applicable), network, running ->
                running ?: session?.toStrategyLanes(currentNetworkScopeKey = network)?.let { lanes ->
                    lanes.copy(lanes = lanes.lanes.map { it.copy(applicable = it.id in applicable) }.toImmutableList())
                }
            }.distinctUntilChanged()
                .flowOn(dispatchers.default)

        // A lane offers its strategy only when applying it can succeed and would change the settings.
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun storedTest(): Flow<Pair<DiagnosticScanSession?, Set<String>>> =
            history.observeLatestStrategyTest().flatMapLatest { session ->
                val report = session?.report?.strategyProbeReport
                if (report == null) {
                    flowOf(session to emptySet())
                } else {
                    applier.applicableIds(report).map { session to it }
                }
            }

        // The session this screen started. A scan of another kind, started elsewhere, is not this screen's
        // to show or to stop.
        @Volatile private var ownSessionId: String? = null
        private val starting = Mutex()

        private fun ScanProgress.isStrategyTest() = strategyProbeProgress != null || sessionId == ownSessionId

        /**
         * Stops the running strategy test, or starts the bundled one. Returns false when the Diagnostics
         * screen must take over: another scan runs, or the start asks the user something.
         */
        suspend fun toggleTest(): Boolean {
            // A second tap while the first start is still in flight does nothing.
            if (!starting.tryLock()) return true
            try {
                val active = timeline.activeScanProgress.value
                return when {
                    active != null -> active.isStrategyTest().also { own -> if (own) scans.cancelActiveScan() }
                    scans.hasActiveHomeRun() -> false
                    else -> startTest()
                }
            } finally {
                starting.unlock()
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun startTest(): Boolean =
            try {
                val result = scans.startScan(ScanPathMode.RAW_PATH, AutomaticProbingProfileId)
                ownSessionId = (result as? DiagnosticsManualScanStartResult.Started)?.sessionId
                ownSessionId != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.withTag(LogTags.UI).w(failure) { "Strategy test start refused; Diagnostics takes over" }
                false
            }

        /** Applies the strategy of one lane of the last stored test. False when it cannot be applied. */
        suspend fun applyLane(id: String): Boolean {
            val report =
                history
                    .observeLatestStrategyTest()
                    .first()
                    ?.report
                    ?.strategyProbeReport
            return report != null && applier.apply(report, id)
        }

        // Each candidate the running test reports becomes a lane, in the order the test reaches them.
        private fun runningTest(): Flow<StrategyLanesUiState?> =
            timeline.activeScanProgress
                .scan(null as RunningTest?) { running, progress ->
                    when {
                        progress == null || !progress.isStrategyTest() -> {
                            null
                        }

                        // A new session starts from no lanes, even when no gap separated it from the last.
                        else -> {
                            (
                                running?.takeIf { it.sessionId == progress.sessionId }
                                    ?: RunningTest(progress.sessionId)
                            ).let { it.copy(lanes = it.lanes.withProgress(progress)) }
                        }
                    }
                }.map { it?.lanes }

        private fun currentNetworkScopeKey(): Flow<String?> =
            handovers
                .observe()
                .map { handover -> handover.currentFingerprint?.scopeKey() }
                .onStart { emit(runCatching { fingerprints.capture() }.getOrNull()?.scopeKey()) }
                // A moment without a network keeps the last known one, so the mark does not flicker.
                .runningReduce { last, next -> next ?: last }
                .flowOn(dispatchers.io)
    }

private data class RunningTest(
    val sessionId: String,
    val lanes: StrategyLanesUiState = StrategyLanesUiState(persistentListOf(), finishedAt = 0L),
)

private fun StrategyLanesUiState.withProgress(progress: ScanProgress): StrategyLanesUiState {
    val step = progress.strategyProbeProgress
    val known = lanes.filterNot { it.id == step?.candidateId }
    val all = known + listOfNotNull(step?.toLane(progress.latestProbeOutcome))
    return copy(
        lanes = all.takeLast(MaxStrategyLanes).toImmutableList(),
        hiddenCount = hiddenCount + (all.size - MaxStrategyLanes).coerceAtLeast(0),
        progress = StrategyTestProgress(progress.completedSteps, progress.totalSteps),
    )
}

/**
 * The engine reports a candidate three ways: started (no sites, no outcome), not run (no sites, an outcome),
 * and finished (its site count). A candidate that did not run is no result, so it gets no lane while the
 * test runs; the stored result lists it as skipped.
 */
private fun StrategyProbeLiveProgress.toLane(outcome: String?): StrategyLaneUiState? =
    if (totalTargets <= 0 && outcome != null) {
        null
    } else {
        val opened = succeededTargets.coerceIn(0, totalTargets.coerceAtLeast(0))
        StrategyLaneUiState(
            label = candidateLabel,
            outcome =
                when {
                    totalTargets <= 0 -> StrategyLaneOutcome.Running
                    opened == totalTargets -> StrategyLaneOutcome.Passed
                    opened > 0 -> StrategyLaneOutcome.Partial
                    else -> StrategyLaneOutcome.Blocked
                },
            succeededTargets = opened,
            totalTargets = totalTargets.coerceAtLeast(0),
            latencyMs = null,
            recommended = false,
            id = candidateId,
        )
    }

/**
 * A test with no candidates gives no lanes. A network counts as another one only when both keys are known:
 * an old row, or a moment without a network, must not mark the lanes.
 */
internal fun DiagnosticScanSession.toStrategyLanes(currentNetworkScopeKey: String?): StrategyLanesUiState? =
    report?.strategyProbeReport?.toStrategyLanes(finishedAt ?: startedAt, report?.results.orEmpty())?.copy(
        otherNetwork =
            networkScopeKey != null && currentNetworkScopeKey != null && networkScopeKey != currentNetworkScopeKey,
    )

// The engine tests the strategy in use as a candidate of its own, under this id.
private const val CurrentStrategyCandidateId = "baseline_current"

internal fun StrategyProbeReport.toStrategyLanes(
    finishedAt: Long,
    results: List<ProbeResult> = emptyList(),
): StrategyLanesUiState? {
    val candidates = tcpCandidates + quicCandidates
    // A family names a lane only when no other candidate of the test shares it.
    val loneFamilies =
        candidates
            .groupingBy { it.family }
            .eachCount()
            .filterValues { it == 1 }
            .keys
    val ranked =
        (
            tcpCandidates.map { it.toLane(recommendation?.tcpCandidateId, loneFamilies, results) } +
                quicCandidates.map { it.toLane(recommendation?.quicCandidateId, loneFamilies, results) }
        ).sortedWith(
            // The winner and the strategy in use come first, so the cap never hides them.
            compareByDescending<StrategyLaneUiState> { it.recommended }
                .thenByDescending { it.current }
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

private fun StrategyProbeCandidateSummary.toLane(
    recommendedId: String?,
    loneFamilies: Set<String>,
    results: List<ProbeResult>,
): StrategyLaneUiState {
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
        current = id == CurrentStrategyCandidateId,
        family = if (id == CurrentStrategyCandidateId) id else family.takeIf { it in loneFamilies },
        id = id,
        stage = if (laneOutcome == StrategyLaneOutcome.Blocked) results.failureStageOf(label) else null,
    )
}

// The engine names a candidate's probe results "<candidate label> · <host>". A failed TLS probe carries the
// stage of the attempt it reports; an HTTP probe that got an answer from a middlebox is marked by the engine.
private fun List<ProbeResult>.failureStageOf(candidateLabel: String): StrategyLaneStage? =
    asSequence()
        .filter { it.probeType.startsWith("strategy_") && it.target.substringBeforeLast(" · ") == candidateLabel }
        .mapNotNull { result ->
            fun detail(key: String) = result.details.firstOrNull { it.key == key }?.value
            when (detail("failureStage") ?: detail("${detail("tlsReportedAttempt")}FailureStage")) {
                "tcp_connect", "socks5_negotiation", "stream_setup" -> StrategyLaneStage.Tcp
                "tls_handshake" -> StrategyLaneStage.Tls
                "http" -> StrategyLaneStage.Http
                else -> null
            }
        }.groupingBy { it }
        .eachCount()
        .entries
        // Most sites win; on a tie, the earlier stage, because nothing gets past it.
        .maxWithOrNull(compareBy<Map.Entry<StrategyLaneStage, Int>> { it.value }.thenByDescending { it.key })
        ?.key

/** What the user can do with the lanes. The work outlives the screen, so it runs in the view model's scope. */
internal class ConnectionStrategyLaneActions(
    private val source: ConnectionStrategyLanesSource,
    private val scope: CoroutineScope,
    private val emit: suspend (ConnectionEffect) -> Unit,
    private val applyFailed: () -> String,
) {
    fun toggleTest() {
        scope.launch { if (!source.toggleTest()) emit(ConnectionEffect.OpenStrategyTest) }
    }

    fun apply(laneId: String) {
        // The strategy row above the lanes shows the new strategy, so only a failure needs words.
        scope.launch { if (!source.applyLane(laneId)) emit(ConnectionEffect.Message(applyFailed())) }
    }
}
