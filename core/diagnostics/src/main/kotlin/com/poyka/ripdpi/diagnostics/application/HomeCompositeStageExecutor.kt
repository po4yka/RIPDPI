@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
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
    ) {
        private companion object {
            private val log = Logger.withTag("HomeAnalysis")
            private const val TimedOutStageRecoveryTimeoutMs = 5_000L
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
                diagnosticsScanController.startScan(
                    pathMode = spec.pathMode,
                    selectedProfileId = spec.profileId,
                    skipActiveScanCheck = true,
                    scanDeadlineMs = stageTimeoutMs(spec, quickScan) - 30_000L,
                    maxCandidates = maxCandidates,
                    targetOverrides = targetOverrides,
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
                onFailure = {
                    markStageFailure(
                        progressState = progressState,
                        runId = runId,
                        stageIndex = stageIndex,
                        headline = "${spec.label} failed",
                        summary = it.message ?: "Unable to start ${spec.label.lowercase()}.",
                    )
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
            val sessionFinished =
                diagnosticsTimelineSource.sessions
                    .map { sessions ->
                        sessions.firstOrNull { it.id == stageSessionId && it.status != "running" }
                    }.filterNotNull()
                    .map { StageSessionSignal.Finished(it) }

            val vpnHalted =
                serviceStateStore.status
                    .drop(1)
                    .filter { pair -> pair.first == AppStatus.Halted }
                    .map { StageSessionSignal.VpnHalted }

            return when (val signal = merge(sessionFinished, vpnHalted).first()) {
                is StageSessionSignal.Finished -> {
                    log.i { "stage ${spec.key} completed status=${signal.session.status}" }
                    stageSessionId to signal.session
                }

                StageSessionSignal.VpnHalted -> {
                    log.w { "VPN halted during stage ${spec.key}" }
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
            runCatching { diagnosticsScanController.cancelActiveScan() }
            val recoveredSession = awaitTimedOutStageRecovery(stageSessionId)
            if (recoveredSession != null) {
                log.i { "stage ${spec.key} recovered after timeout status=${recoveredSession.status}" }
                return stageSessionId to recoveredSession
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

        private suspend fun awaitTimedOutStageRecovery(stageSessionId: String): DiagnosticScanSession? =
            withTimeoutOrNull(TimedOutStageRecoveryTimeoutMs) {
                diagnosticsTimelineSource.sessions
                    .map { sessions ->
                        sessions.firstOrNull { it.id == stageSessionId && it.status != "running" }
                    }.filterNotNull()
                    .first()
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
                    progress.copy(
                        activeStageIndex = stageIndex,
                        activeSessionId = updatedStages.getOrNull(stageIndex)?.sessionId,
                        stages = updatedStages,
                    )
                }
            }
        }
    }
