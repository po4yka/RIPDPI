@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.finalization.RawPathHomeSettlementDisposition
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementContextKind
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementInconclusiveSummaryPrefix
import com.poyka.ripdpi.diagnostics.finalization.evaluateForHomeContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class HomeCompositeStageExecutor
    @Inject
    constructor(
        private val diagnosticsScanController: DiagnosticsScanController,
        private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        private val serviceStateStore: ServiceStateStore,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
    ) {
        internal constructor(
            diagnosticsScanController: DiagnosticsScanController,
            diagnosticsTimelineSource: DiagnosticsTimelineSource,
            serviceStateStore: ServiceStateStore,
        ) : this(
            diagnosticsScanController,
            diagnosticsTimelineSource,
            serviceStateStore,
            UnavailableInPathRuntimeCoordinator,
        )

        private companion object {
            private val log = Logger.withTag("HomeAnalysis")
            private const val TimedOutStageRecoveryTimeoutMs = 5_000L
        }

        suspend fun requireInPathRuntime(
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ): Boolean {
            val (status, mode) = serviceStateStore.status.value
            val available =
                status == AppStatus.Running &&
                    (mode != Mode.VPN || currentVpnInPathRouteIsAvailable())
            if (!available) {
                updateStage(progressState, runId, stageIndex) { current ->
                    current.copy(
                        status = DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                        headline = "${spec.label} unavailable",
                        summary =
                            if (status == AppStatus.Running) {
                                "The active VPN route was not observable for the in-path comparison."
                            } else {
                                "The owner-controlled runtime was not ready for the in-path comparison."
                            },
                        unavailableReason =
                            if (status == AppStatus.Running) {
                                DiagnosticsHomeCompositeStageUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED
                            } else {
                                DiagnosticsHomeCompositeStageUnavailableReason.SERVICE_NOT_RUNNING
                            },
                    )
                }
            }
            return available
        }

        private suspend fun currentVpnInPathRouteIsAvailable(): Boolean {
            val lease = runtimeCoordinator.acquireInPathRouteLease() ?: return false
            return runtimeCoordinator.isInPathRouteLeaseCurrent(lease)
        }

        suspend fun cancelRunStages(
            runId: String,
            progressState: StateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
        ) {
            var cancellationFailure: Throwable? = null
            val recordedSessionIds =
                progressState.value[runId]
                    ?.stages
                    ?.mapNotNull(DiagnosticsHomeCompositeStageSummary::sessionId)
                    .orEmpty()
            try {
                (diagnosticsScanController.activeSessionIdsOwnedBy(runId) + recordedSessionIds)
                    .distinct()
                    .forEach { sessionId ->
                        cancellationFailure =
                            cancellationFailure.withSuppressed(
                                cancelRunSession(runId, sessionId),
                            )
                    }
            } finally {
                runCatching { diagnosticsScanController.releaseSessionsOwnedBy(runId) }
                    .exceptionOrNull()
                    ?.let { releaseFailure ->
                        cancellationFailure = cancellationFailure.withSuppressed(releaseFailure)
                    }
            }
            cancellationFailure?.let { throw it }
        }

        suspend fun cancelRunAndSetTerminalStatus(
            runId: String,
            progressState: StateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            updateRunStatus: (String, DiagnosticsHomeCompositeRunStatus) -> Unit,
            beforeTerminalStatus: suspend () -> Unit = {},
        ) {
            val failure =
                runCatching {
                    cancelRunStages(runId, progressState)
                    beforeTerminalStatus()
                    updateRunStatus(runId, DiagnosticsHomeCompositeRunStatus.CANCELLED)
                }.exceptionOrNull() ?: return
            runCatching { beforeTerminalStatus() }
            updateRunStatus(
                runId,
                if (
                    failure is CancellationException &&
                    failure.suppressed.all { it is CancellationException }
                ) {
                    DiagnosticsHomeCompositeRunStatus.CANCELLED
                } else {
                    DiagnosticsHomeCompositeRunStatus.FAILED
                },
            )
            throw failure
        }

        private suspend fun cancelRunSession(
            runId: String,
            sessionId: String,
        ): Throwable? {
            val failure =
                runCatching { diagnosticsScanController.cancelScan(sessionId) }.exceptionOrNull()
            return when (failure) {
                null -> {
                    null
                }

                else -> {
                    log.w(failure) { "failed to cancel run session: runId=$runId sessionId=$sessionId" }
                    failure
                }
            }
        }

        suspend fun executeStage(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            maxCandidates: Int? = null,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
        ): Pair<String, DiagnosticScanSession>? {
            val stageSessionId =
                launchStageSession(
                    runId = runId,
                    stageIndex = stageIndex,
                    spec = spec,
                    quickScan = false,
                    progressState = progressState,
                    maxCandidates = maxCandidates,
                    targetOverrides = targetOverrides,
                ) ?: return null
            return awaitStageSignal(runId, stageIndex, spec, stageSessionId, progressState)
        }

        suspend fun launchStageSession(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            quickScan: Boolean,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            maxCandidates: Int? = null,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
        ): String? {
            updateStage(progressState, runId, stageIndex) { stage ->
                stage.copy(
                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                    headline = "${spec.label} running",
                    summary = "Starting ${spec.label.lowercase()}.",
                )
            }
            log.i {
                "stage ${spec.key} started (profile=${spec.profileId} timeout=${stageTimeoutMs(spec, quickScan)}ms)"
            }
            val stageSessionId =
                startStageSession(
                    runId = runId,
                    stageIndex = stageIndex,
                    spec = spec,
                    quickScan = quickScan,
                    progressState = progressState,
                    maxCandidates = maxCandidates,
                    targetOverrides = targetOverrides,
                ) ?: return null
            updateStage(progressState, runId, stageIndex) { current ->
                current.copy(
                    sessionId = stageSessionId,
                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                    headline = "${spec.label} running",
                    summary = "Collecting diagnostics for ${spec.label.lowercase()}.",
                )
            }
            return stageSessionId
        }

        private suspend fun startStageSession(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            quickScan: Boolean = false,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            maxCandidates: Int? = null,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
        ): String? =
            runCatching {
                val pathMode = requireNotNull(spec.pathMode) { "Stage ${spec.key} does not start a scan session" }
                diagnosticsScanController.startScanOwnedBy(
                    ownerId = runId,
                    pathMode = pathMode,
                    selectedProfileId = spec.profileId,
                    skipActiveScanCheck = true,
                    scanDeadlineMs = stageScanDeadlineMs(spec, quickScan),
                    maxCandidates = maxCandidates,
                    targetOverrides = targetOverrides,
                    resumeRuntimeAfterRawPath = pathMode == ScanPathMode.RAW_PATH,
                )
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is DiagnosticsManualScanStartResult.Started -> {
                            result.sessionId
                        }

                        is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                            markStageFailure(
                                progressState = progressState,
                                runId = runId,
                                stageIndex = stageIndex,
                                headline = "${spec.label} unavailable",
                                summary = "Another diagnostics run is already active.",
                            )
                            null
                        }
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) {
                        throw failure
                    }
                    if (failure is InPathRuntimeUnavailableException) {
                        updateStage(progressState, runId, stageIndex) { current ->
                            current.copy(
                                status = DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                                headline = "${spec.label} unavailable",
                                summary = failure.message ?: "The in-path runtime became unavailable.",
                                unavailableReason = failure.reason,
                            )
                        }
                    } else {
                        markStageFailure(
                            progressState = progressState,
                            runId = runId,
                            stageIndex = stageIndex,
                            headline = "${spec.label} failed",
                            summary = failure.message ?: "Unable to start ${spec.label.lowercase()}.",
                        )
                    }
                    null
                },
            )

        suspend fun awaitStageSignal(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            stageSessionId: String,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
        ): Pair<String, DiagnosticScanSession>? {
            val pathMode = requireNotNull(spec.pathMode) { "Stage ${spec.key} does not own a scan session" }
            val sessionFinished =
                diagnosticsTimelineSource.sessions
                    .map { sessions ->
                        sessions.firstOrNull { it.id == stageSessionId && it.status != "running" }
                    }.filterNotNull()
                    .map { StageSessionSignal.Finished(it) }

            val rawSessionSettled =
                combine(
                    diagnosticsTimelineSource.sessions,
                    diagnosticsTimelineSource.contexts,
                ) { sessions, contexts ->
                    val terminalSession =
                        sessions.firstOrNull { session ->
                            session.id == stageSessionId && session.status != "running"
                        }
                    terminalSession?.let { session ->
                        contexts
                            .firstOrNull { context ->
                                context.sessionId == stageSessionId &&
                                    context.contextKind == RawPathSettlementContextKind
                            }?.let { context ->
                                StageSessionSignal.RawPathSettled(session, context.rawPathExecutionResult)
                            }
                    }
                }.filterNotNull()

            val vpnHalted =
                serviceStateStore.status
                    .drop(1)
                    .filter { pair -> pair.first == AppStatus.Halted }
                    .map { StageSessionSignal.VpnHalted }

            val signal =
                if (pathMode == ScanPathMode.RAW_PATH) {
                    rawSessionSettled.first()
                } else {
                    merge(sessionFinished, vpnHalted).first()
                }

            return when (signal) {
                is StageSessionSignal.Finished -> {
                    log.i { "stage ${spec.key} completed status=${signal.session.status}" }
                    stageSessionId to signal.session
                }

                is StageSessionSignal.RawPathSettled -> {
                    resolveRawPathSettlementSignal(
                        signal = signal,
                        runId = runId,
                        stageIndex = stageIndex,
                        spec = spec,
                        stageSessionId = stageSessionId,
                        progressState = progressState,
                    )
                }

                StageSessionSignal.VpnHalted -> {
                    log.w { "VPN halted during stage ${spec.key}" }
                    retireInterruptedStage(stageSessionId)
                    markStageFailure(
                        progressState = progressState,
                        runId = runId,
                        stageIndex = stageIndex,
                        headline = "${spec.label} failed",
                        summary = "VPN service stopped while the stage was running.",
                    )
                    null
                }
            }
        }

        private suspend fun retireInterruptedStage(stageSessionId: String) {
            withContext(NonCancellable) {
                runCatching { diagnosticsScanController.cancelScan(stageSessionId) }
                    .onFailure { failure ->
                        log.w(failure) { "failed to cancel interrupted session: $stageSessionId" }
                    }
                awaitTimedOutStageRecovery(stageSessionId, ScanPathMode.IN_PATH)
            }
        }

        suspend fun executeStageWithTimeout(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            quickScan: Boolean = false,
            maxCandidates: Int? = null,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
        ): Pair<String, DiagnosticScanSession>? =
            run {
                val stageSessionId =
                    launchStageSession(
                        runId = runId,
                        stageIndex = stageIndex,
                        spec = spec,
                        quickScan = quickScan,
                        progressState = progressState,
                        maxCandidates = maxCandidates,
                        targetOverrides = targetOverrides,
                    ) ?: return@run null

                withTimeoutOrNull(stageTimeoutMs(spec, quickScan)) {
                    awaitStageSignal(runId, stageIndex, spec, stageSessionId, progressState)
                } ?: handleTimedOutStage(
                    runId = runId,
                    stageIndex = stageIndex,
                    spec = spec,
                    quickScan = quickScan,
                    stageSessionId = stageSessionId,
                    progressState = progressState,
                )
            }

        private suspend fun handleTimedOutStage(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            quickScan: Boolean,
            stageSessionId: String,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
        ): Pair<String, DiagnosticScanSession>? {
            log.w { "stage ${spec.key} timed out after ${stageTimeoutMs(spec, quickScan)}ms" }
            runCatching { diagnosticsScanController.cancelScan(stageSessionId) }
                .onFailure { failure -> log.w(failure) { "failed to cancel timed-out session: $stageSessionId" } }
            val recoveredSignal = awaitTimedOutStageRecovery(stageSessionId, requireNotNull(spec.pathMode))
            if (recoveredSignal != null) {
                return when (recoveredSignal) {
                    is StageSessionSignal.Finished -> {
                        log.i { "stage ${spec.key} recovered after timeout status=${recoveredSignal.session.status}" }
                        stageSessionId to recoveredSignal.session
                    }

                    is StageSessionSignal.RawPathSettled -> {
                        resolveRawPathSettlementSignal(
                            signal = recoveredSignal,
                            runId = runId,
                            stageIndex = stageIndex,
                            spec = spec,
                            stageSessionId = stageSessionId,
                            progressState = progressState,
                        )
                    }

                    StageSessionSignal.VpnHalted -> {
                        null
                    }
                }
            }
            markStageFailure(
                progressState = progressState,
                runId = runId,
                stageIndex = stageIndex,
                headline = "${spec.label} timed out",
                summary = "The stage did not complete within the allowed time.",
            )
            return null
        }

        private suspend fun awaitTimedOutStageRecovery(
            stageSessionId: String,
            pathMode: ScanPathMode,
        ): StageSessionSignal? =
            withTimeoutOrNull(TimedOutStageRecoveryTimeoutMs) {
                if (pathMode == ScanPathMode.RAW_PATH) {
                    combine(
                        diagnosticsTimelineSource.sessions,
                        diagnosticsTimelineSource.contexts,
                    ) { sessions, contexts ->
                        val terminalSession =
                            sessions.firstOrNull { session ->
                                session.id == stageSessionId && session.status != "running"
                            }
                        terminalSession?.let { session ->
                            contexts
                                .firstOrNull { context ->
                                    context.sessionId == stageSessionId &&
                                        context.contextKind == RawPathSettlementContextKind
                                }?.let { context ->
                                    StageSessionSignal.RawPathSettled(session, context.rawPathExecutionResult)
                                }
                        }
                    }.filterNotNull()
                        .first()
                } else {
                    diagnosticsTimelineSource.sessions
                        .map { sessions ->
                            sessions.firstOrNull { it.id == stageSessionId && it.status != "running" }
                        }.filterNotNull()
                        .map { StageSessionSignal.Finished(it) }
                        .first()
                }
            }

        private fun resolveRawPathSettlementSignal(
            signal: StageSessionSignal.RawPathSettled,
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            stageSessionId: String,
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
        ): Pair<String, DiagnosticScanSession>? {
            val evaluation = signal.result?.evaluateForHomeContinuation()
            return when (evaluation?.disposition) {
                RawPathHomeSettlementDisposition.USABLE -> {
                    log.i { "stage ${spec.key} settled status=${signal.session.status} receipt=${evaluation.reason}" }
                    stageSessionId to signal.session
                }

                RawPathHomeSettlementDisposition.SUPERSEDED_BY_USER_STOP -> {
                    throw HomeRawPathSupersededByUserStopException(stageSessionId)
                }

                RawPathHomeSettlementDisposition.INCONCLUSIVE,
                null,
                -> {
                    val reason = evaluation?.reason ?: "missing_or_malformed_receipt"
                    markStageFailure(
                        progressState = progressState,
                        runId = runId,
                        stageIndex = stageIndex,
                        headline = "${spec.label} inconclusive",
                        summary = "$RawPathSettlementInconclusiveSummaryPrefix: $reason",
                    )
                    null
                }
            }
        }

        fun markStageFailure(
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            runId: String,
            stageIndex: Int,
            headline: String,
            summary: String,
        ) {
            updateStage(progressState, runId, stageIndex) { current ->
                current.copy(
                    status = DiagnosticsHomeCompositeStageStatus.FAILED,
                    headline = headline,
                    summary = summary,
                )
            }
        }

        fun updateStage(
            progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
            runId: String,
            stageIndex: Int,
            transform: (DiagnosticsHomeCompositeStageSummary) -> DiagnosticsHomeCompositeStageSummary,
        ) {
            progressState.update { current ->
                current.updatedRun(runId) { progress ->
                    val updatedStages = progress.stages.updated(stageIndex, transform)
                    val activeStageIndex =
                        updatedStages.activeStageIndexAfterUpdate(progress.activeStageIndex, stageIndex)
                    progress.copy(
                        activeStageIndex = activeStageIndex,
                        activeSessionId = activeStageIndex?.let(updatedStages::getOrNull)?.sessionId,
                        stages = updatedStages,
                    )
                }
            }
        }
    }

private object UnavailableInPathRuntimeCoordinator : DiagnosticsRuntimeCoordinator {
    override suspend fun runRawPathScan(block: suspend () -> Unit) = unavailableRawPathResult(block)

    override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) = unavailableRawPathResult(block)
}

private suspend fun unavailableRawPathResult(block: suspend () -> Unit): com.poyka.ripdpi.data.RawPathExecutionResult {
    block()
    return com.poyka.ripdpi.data.RawPathExecutionResult(
        settlement =
            com.poyka.ripdpi.data.RawPathExecutionSettlement(
                rawWindowGeneration = 0L,
                resumeIntentGeneration = 0L,
                outcome = com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome.RestoreNotRequired,
                runtimeWasRunning = false,
                resumeRequired = false,
                postRuntimeContext =
                    com.poyka.ripdpi.data
                        .RawPathRuntimeContext(),
            ),
        executionOutcome = com.poyka.ripdpi.data.RawPathExecutionOutcome.Completed,
    )
}

private fun Throwable?.withSuppressed(additional: Throwable?): Throwable? =
    additional?.let { next ->
        this?.apply {
            if (this !== next) addSuppressed(next)
        } ?: next
    } ?: this
