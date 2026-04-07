package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.services.NetworkHandoverEvent
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val DpiFullStageTimeoutMs = 240_000L
private const val StrategyProbeStageTimeoutMs = 300_000L
private const val DefaultStageTimeoutMs = 120_000L
private const val StageRetryDelayMs = 2_000L

private data class HomeCompositeStageSpec(
    val key: String,
    val label: String,
    val profileId: String,
    val pathMode: ScanPathMode,
)

private val HomeCompositeStageSpecs =
    listOf(
        HomeCompositeStageSpec(
            key = "automatic_audit",
            label = "Automatic audit",
            profileId = "automatic-audit",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "default_connectivity",
            label = "Default diagnostics",
            profileId = "default",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "dpi_full",
            label = "DPI detector full",
            profileId = "ru-dpi-full",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "dpi_strategy",
            label = "DPI strategy probe",
            profileId = "ru-dpi-strategy",
            pathMode = ScanPathMode.RAW_PATH,
        ),
    )

private sealed interface StageSessionSignal {
    data class Finished(
        val session: DiagnosticScanSession,
    ) : StageSessionSignal

    data object VpnHalted : StageSessionSignal
}

private fun stageTimeoutMs(spec: HomeCompositeStageSpec): Long =
    when (spec.profileId) {
        "ru-dpi-full" -> DpiFullStageTimeoutMs
        "automatic-audit", "ru-dpi-strategy" -> StrategyProbeStageTimeoutMs
        else -> DefaultStageTimeoutMs
    }

private inline fun Map<String, DiagnosticsHomeCompositeProgress>.updatedRun(
    runId: String,
    transform: (DiagnosticsHomeCompositeProgress) -> DiagnosticsHomeCompositeProgress,
): Map<String, DiagnosticsHomeCompositeProgress> = this[runId]?.let { this + (runId to transform(it)) } ?: this

private inline fun List<DiagnosticsHomeCompositeStageSummary>.updated(
    index: Int,
    transform: (DiagnosticsHomeCompositeStageSummary) -> DiagnosticsHomeCompositeStageSummary,
): List<DiagnosticsHomeCompositeStageSummary> =
    mapIndexed { currentIndex, value -> if (currentIndex == index) transform(value) else value }

@Singleton
class DefaultDiagnosticsHomeCompositeRunService
    @Inject
    constructor(
        private val diagnosticsScanController: DiagnosticsScanController,
        private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        private val serviceStateStore: ServiceStateStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsHomeCompositeRunService {
        private companion object {
            private val log = Logger.withTag("HomeAnalysis")
        }

        private val progressState = MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>(emptyMap())
        private val completedRuns = ConcurrentHashMap<String, DiagnosticsHomeCompositeOutcome>()

        override suspend fun startHomeAnalysis(): DiagnosticsHomeCompositeRunStarted {
            val runId = UUID.randomUUID().toString()
            progressState.update { current ->
                current +
                    (
                        runId to
                            DiagnosticsHomeCompositeProgress(
                                runId = runId,
                                fingerprintHash = diagnosticsHomeWorkflowService.currentFingerprintHash(),
                                stages =
                                    HomeCompositeStageSpecs.map { spec ->
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = spec.key,
                                            stageLabel = spec.label,
                                            profileId = spec.profileId,
                                            pathMode = spec.pathMode,
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "${spec.label} pending",
                                            summary = "Waiting to run.",
                                        )
                                    },
                            )
                    )
            }
            scope.launch {
                executeRun(runId)
            }
            return DiagnosticsHomeCompositeRunStarted(runId = runId)
        }

        override fun observeHomeRun(runId: String): Flow<DiagnosticsHomeCompositeProgress> =
            progressState
                .map { runs -> runs[runId] }
                .filterNotNull()

        override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome {
            completedRuns[runId]?.let { return it }
            return observeHomeRun(runId)
                .map { it.outcome }
                .filterNotNull()
                .first()
        }

        override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = completedRuns[runId]

        private suspend fun executeRun(runId: String) {
            log.i { "started runId=$runId stages=${HomeCompositeStageSpecs.size}" }
            val auditSpec = HomeCompositeStageSpecs[0]
            val auditIndex = 0
            val (eventCollector, networkEvents) = startNetworkEventCollector()

            val auditCompletedSession = executeStageWithTimeout(runId, auditIndex, auditSpec)
            if (auditCompletedSession == null) {
                handleAuditStageFailed(runId, auditIndex, auditSpec)
                eventCollector.cancel()
                finalizeRun(
                    runId,
                    auditOutcome = null,
                    coverageNote = null,
                    dnsIssuesDetected = false,
                    networkChanged = false,
                )
                return
            }

            val auditOutcomeAfterAudit = processAuditStageResult(runId, auditIndex, auditSpec, auditCompletedSession)
            if (auditOutcomeAfterAudit == null) {
                eventCollector.cancel()
                finalizeRun(
                    runId,
                    auditOutcome = null,
                    coverageNote = null,
                    dnsIssuesDetected = false,
                    networkChanged = false,
                )
                return
            }

            runParallelMiddleStages(runId)

            val auditOutcome = runDpiStrategyStage(runId, auditOutcomeAfterAudit)
            eventCollector.cancel()
            val networkChangedDuringRun = networkEvents.isNotEmpty()
            val coverageNote = crossValidateHomeStrategy(runId, progressState, scanRecordStore, json)
            val dnsIssuesDetected = detectHomeRunDnsIssues(runId, progressState, scanRecordStore, json)
            finalizeRun(runId, auditOutcome, coverageNote, dnsIssuesDetected, networkChangedDuringRun)
            log.i {
                val outcome = completedRuns[runId]
                "run completed: completed=${outcome?.completedStageCount}" +
                    " failed=${outcome?.failedStageCount}" +
                    " skipped=${outcome?.skippedStageCount}"
            }
        }

        private fun startNetworkEventCollector(): Pair<Job, MutableList<NetworkHandoverEvent>> {
            val networkEvents = mutableListOf<com.poyka.ripdpi.services.NetworkHandoverEvent>()
            val job =
                scope.launch {
                    networkHandoverMonitor.events.collect { event ->
                        if (event.isActionable) networkEvents += event
                    }
                }
            return job to networkEvents
        }

        /**
         * Records the audit stage as completed and finalizes the home audit.
         * Returns the finalized [DiagnosticsHomeAuditOutcome], or null if the audit session
         * did not complete (e.g. network unavailable), in which case remaining stages are skipped.
         */
        private suspend fun processAuditStageResult(
            runId: String,
            auditIndex: Int,
            auditSpec: HomeCompositeStageSpec,
            auditCompletedSession: Pair<String, DiagnosticScanSession>,
        ): DiagnosticsHomeAuditOutcome? {
            val auditSessionId = auditCompletedSession.first
            val auditSession = auditCompletedSession.second
            var auditOutcome = recordAuditStageCompleted(runId, auditIndex, auditSpec, auditSessionId, auditSession)
            if (auditSession.status != "completed") {
                skipRemainingStages(runId, reason = "Skipped due to network unavailability.")
                return null
            }
            val finalizedAuditOutcome = diagnosticsHomeWorkflowService.finalizeHomeAudit(auditSessionId)
            auditOutcome = finalizedAuditOutcome
            log.i { "audit finalized actionable=${finalizedAuditOutcome.actionable}" }
            updateStage(runId, auditIndex) { current ->
                current.copy(
                    headline = finalizedAuditOutcome.headline,
                    summary = finalizedAuditOutcome.summary,
                    recommendationContributor = finalizedAuditOutcome.actionable,
                )
            }
            return auditOutcome
        }

        /** Runs default_connectivity (index 1) and dpi_full (index 2) in parallel with one retry each. */
        private suspend fun runParallelMiddleStages(runId: String) {
            coroutineScope {
                HomeCompositeStageSpecs.drop(1).dropLast(1).forEachIndexed { i, spec ->
                    val stageIndex = i + 1
                    launch {
                        var result = executeStage(runId, stageIndex, spec)
                        if (result == null) {
                            delay(StageRetryDelayMs)
                            result = executeStage(runId, stageIndex, spec)
                        }
                        if (result != null) {
                            val (sessionId, completedSession) = result
                            val completedSummary =
                                buildCompletedStageSummary(
                                    spec,
                                    sessionId,
                                    completedSession,
                                    scanRecordStore,
                                    json,
                                )
                            updateStage(runId, stageIndex) { completedSummary }
                        }
                    }
                }
            }
        }

        private fun handleAuditStageFailed(
            runId: String,
            auditIndex: Int,
            auditSpec: HomeCompositeStageSpec,
        ) {
            // Stage either timed out or was marked failed by VPN-halt detection inside
            // executeStage. Ensure the stage is recorded as failed if it is still running
            // (the VPN-halt path already calls markStageFailure; the timeout path does not).
            val currentStageStatus =
                progressState.value[runId]
                    ?.stages
                    ?.getOrNull(auditIndex)
                    ?.status
            if (currentStageStatus == DiagnosticsHomeCompositeStageStatus.RUNNING) {
                markStageFailure(
                    runId = runId,
                    stageIndex = auditIndex,
                    headline = "${auditSpec.label} timed out",
                    summary = "The audit stage did not complete within the allowed time.",
                )
            }
            skipRemainingStages(runId, reason = "Skipped due to audit stage failure.")
        }

        private fun skipRemainingStages(
            runId: String,
            reason: String,
        ) {
            HomeCompositeStageSpecs.drop(1).forEachIndexed { i, spec ->
                val stageIndex = i + 1
                log.i { "stage ${spec.key} skipped: $reason" }
                updateStage(runId, stageIndex) { current ->
                    current.copy(
                        status = DiagnosticsHomeCompositeStageStatus.SKIPPED,
                        headline = "${spec.label} skipped",
                        summary = reason,
                    )
                }
            }
        }

        private suspend fun recordAuditStageCompleted(
            runId: String,
            auditIndex: Int,
            auditSpec: HomeCompositeStageSpec,
            auditSessionId: String,
            auditSession: DiagnosticScanSession,
        ): DiagnosticsHomeAuditOutcome {
            val completedSummary =
                buildCompletedStageSummary(
                    spec = auditSpec,
                    sessionId = auditSessionId,
                    session = auditSession,
                    scanRecordStore = scanRecordStore,
                    json = json,
                )
            val outcome =
                DiagnosticsHomeAuditOutcome(
                    sessionId = completedSummary.sessionId.orEmpty(),
                    fingerprintHash = diagnosticsHomeWorkflowService.currentFingerprintHash(),
                    actionable = completedSummary.recommendationContributor,
                    headline = completedSummary.headline,
                    summary = completedSummary.summary,
                )
            updateStage(runId, auditIndex) { completedSummary }
            return outcome
        }

        private suspend fun runDpiStrategyStage(
            runId: String,
            currentAuditOutcome: DiagnosticsHomeAuditOutcome?,
        ): DiagnosticsHomeAuditOutcome? {
            var auditOutcome = currentAuditOutcome
            val dpiStrategySpec = HomeCompositeStageSpecs.last()
            val dpiStrategyIndex = HomeCompositeStageSpecs.lastIndex
            var dpiStrategyResult = executeStageWithTimeout(runId, dpiStrategyIndex, dpiStrategySpec)
            if (dpiStrategyResult == null) {
                delay(StageRetryDelayMs)
                dpiStrategyResult = executeStageWithTimeout(runId, dpiStrategyIndex, dpiStrategySpec)
            }
            if (dpiStrategyResult != null) {
                val (dpiStrategySessionId, dpiStrategySession) = dpiStrategyResult
                val dpiStrategySummary =
                    buildCompletedStageSummary(
                        spec = dpiStrategySpec,
                        sessionId = dpiStrategySessionId,
                        session = dpiStrategySession,
                        scanRecordStore = scanRecordStore,
                        json = json,
                    )
                updateStage(runId, dpiStrategyIndex) { dpiStrategySummary }
                // If the audit stage did not produce actionable recommendations,
                // let the strategy probe contribute its own recommendation.
                if (auditOutcome?.actionable != true && dpiStrategySession.status == "completed") {
                    val strategyAuditOutcome =
                        diagnosticsHomeWorkflowService.finalizeHomeAudit(dpiStrategySessionId)
                    if (strategyAuditOutcome.actionable) {
                        auditOutcome = strategyAuditOutcome
                        updateStage(runId, dpiStrategyIndex) { current ->
                            current.copy(
                                headline = strategyAuditOutcome.headline,
                                summary = strategyAuditOutcome.summary,
                                recommendationContributor = true,
                            )
                        }
                    }
                }
            }
            return auditOutcome
        }

        private fun finalizeRun(
            runId: String,
            auditOutcome: DiagnosticsHomeAuditOutcome?,
            coverageNote: String?,
            dnsIssuesDetected: Boolean,
            networkChanged: Boolean,
        ) {
            val outcome =
                buildHomeCompositeOutcome(
                    runId,
                    auditOutcome,
                    coverageNote,
                    dnsIssuesDetected,
                    networkChanged,
                    progressState,
                )
            completedRuns[runId] = outcome
            progressState.update { current ->
                current.updatedRun(runId) { progress ->
                    progress.copy(
                        fingerprintHash = outcome.fingerprintHash,
                        status = DiagnosticsHomeCompositeRunStatus.COMPLETED,
                        activeStageIndex = null,
                        activeSessionId = null,
                        stages = outcome.stageSummaries,
                        outcome = outcome,
                    )
                }
            }
        }

        private suspend fun executeStage(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ): Pair<String, DiagnosticScanSession>? {
            updateStage(runId, stageIndex) { stage ->
                stage.copy(
                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                    headline = "${spec.label} running",
                    summary = "Starting ${spec.label.lowercase()}.",
                )
            }
            log.i { "stage ${spec.key} started (profile=${spec.profileId} timeout=${stageTimeoutMs(spec)}ms)" }
            val stageSessionId = startStageSession(runId, stageIndex, spec) ?: return null
            updateStage(runId, stageIndex) { current ->
                current.copy(
                    sessionId = stageSessionId,
                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                    headline = "${spec.label} running",
                    summary = "Collecting diagnostics for ${spec.label.lowercase()}.",
                )
            }
            return awaitStageSignal(runId, stageIndex, spec, stageSessionId)
        }

        private suspend fun startStageSession(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ): String? =
            runCatching {
                diagnosticsScanController.startScan(
                    pathMode = spec.pathMode,
                    selectedProfileId = spec.profileId,
                    skipActiveScanCheck = true,
                    scanDeadlineMs = stageTimeoutMs(spec) - 30_000L,
                )
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is DiagnosticsManualScanStartResult.Started -> {
                            result.sessionId
                        }

                        is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                            markStageFailure(
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
                        runId = runId,
                        stageIndex = stageIndex,
                        headline = "${spec.label} failed",
                        summary = it.message ?: "Unable to start ${spec.label.lowercase()}.",
                    )
                    null
                },
            )

        private suspend fun awaitStageSignal(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            stageSessionId: String,
        ): Pair<String, DiagnosticScanSession>? {
            val sessionFinished: Flow<StageSessionSignal> =
                diagnosticsTimelineSource.sessions
                    .map { sessions ->
                        sessions.firstOrNull { it.id == stageSessionId && it.status != "running" }
                    }.filterNotNull()
                    .map { StageSessionSignal.Finished(it) }

            val vpnHalted: Flow<StageSessionSignal> =
                serviceStateStore.status
                    .drop(1) // skip current snapshot; only react to future transitions
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
                        runId = runId,
                        stageIndex = stageIndex,
                        headline = "${spec.label} failed",
                        summary = "VPN service stopped while the stage was running.",
                    )
                    null
                }
            }
        }

        private suspend fun executeStageWithTimeout(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ): Pair<String, DiagnosticScanSession>? =
            withTimeoutOrNull(stageTimeoutMs(spec)) {
                executeStage(runId, stageIndex, spec)
            }.also { result ->
                if (result == null) {
                    log.w { "stage ${spec.key} timed out after ${stageTimeoutMs(spec)}ms" }
                    // Signal the native side to stop — otherwise the Rust probe thread
                    // runs orphaned until its own deadline or completion.
                    runCatching { diagnosticsScanController.cancelActiveScan() }
                }
            }

        private fun markStageFailure(
            runId: String,
            stageIndex: Int,
            headline: String,
            summary: String,
        ) {
            updateStage(runId, stageIndex) { current ->
                current.copy(
                    status = DiagnosticsHomeCompositeStageStatus.FAILED,
                    headline = headline,
                    summary = summary,
                )
            }
        }

        private fun updateStage(
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
