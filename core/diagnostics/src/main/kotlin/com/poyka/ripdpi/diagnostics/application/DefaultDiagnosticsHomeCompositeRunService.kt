@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal class HomeCompositePersistencePorts
    @Inject
    constructor(
        val scanRecordStore: DiagnosticsScanRecordStore,
        val comparisonScanCoordinator: ComparisonScanCoordinator,
        val homeRunPersistence: HomeDiagnosticsRunPersistence,
        val probeResultCache: ProbeResultCache,
    )

@Suppress("detekt.LargeClass", "detekt.TooManyFunctions")
@Singleton
internal class DefaultDiagnosticsHomeCompositeRunService
    @Inject
    constructor(
        private val detectionStageRunner: HomeDetectionStageRunner,
        private val detectorCatalogSource: HomeDetectorCatalogSource,
        private val analysisAugmentationSource: HomeAnalysisAugmentationSource,
        private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
        private val diagnosticsProfileCatalog: DiagnosticsProfileCatalog,
        private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
        private val persistencePorts: HomeCompositePersistencePorts,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        private val serviceStateStore: ServiceStateStore,
        private val vpnRouteEvidenceProvider: VpnRouteEvidenceProvider,
        private val stageExecutor: HomeCompositeStageExecutor,
        @param:Named("diagnosticsJson")
        private val json: Json,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsHomeCompositeRunService {
        private companion object {
            private val log = Logger.withTag("HomeAnalysis")
        }

        private val scanRecordStore: DiagnosticsScanRecordStore = persistencePorts.scanRecordStore
        private val comparisonScanCoordinator: ComparisonScanCoordinator = persistencePorts.comparisonScanCoordinator
        private val homeRunPersistence: HomeDiagnosticsRunPersistence = persistencePorts.homeRunPersistence
        private val probeResultCache: ProbeResultCache = persistencePorts.probeResultCache
        private val progressState = MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>(emptyMap())
        private val completedRuns = ConcurrentHashMap<String, DiagnosticsHomeCompositeOutcome>()
        private val packetCaptureCoordinator = HomePacketCaptureTerminalCoordinator(scope)
        private val runDetectionResults = ConcurrentHashMap<String, HomeDetectionStageOutcome>()
        private val runJobs = HomeCompositeRunJobs(scope)
        private val activeProbeSafetyPolicy = ActiveProbeSafetyPolicy()
        private val passiveVpnRouteEvidenceBuilder = PassiveVpnRouteEvidenceBuilder()
        private val detectionStageCoordinator =
            HomeDetectionStageCoordinator(
                detectionStageRunner = detectionStageRunner,
                stageExecutor = stageExecutor,
                progressState = progressState,
                runDetectionResults = runDetectionResults,
            )
        private val outcomeFinalizer =
            HomeCompositeOutcomeFinalizer(
                detectorCatalogSource = detectorCatalogSource,
                analysisAugmentationSource = analysisAugmentationSource,
                networkEdgePreferenceStore = networkEdgePreferenceStore,
                persistencePorts = persistencePorts,
                serviceStateStore = serviceStateStore,
                json = json,
                runtime =
                    HomeCompositeFinalizationRuntime(
                        progressState = progressState,
                        completedRuns = completedRuns,
                        scope = scope,
                    ),
            )

        override suspend fun startHomeAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted {
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
                                            evidenceType = spec.evidenceType,
                                            vantage = spec.vantage,
                                            targetReachability = spec.targetReachability,
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "${spec.label} pending",
                                            summary = "Waiting to run.",
                                        )
                                    },
                            )
                    )
            }
            admitAndLaunchRun(runId, options) { executeRun(runId) }
            return DiagnosticsHomeCompositeRunStarted(runId = runId)
        }

        override fun observeHomeRun(runId: String): Flow<DiagnosticsHomeCompositeProgress> =
            progressState
                .map { runs -> runs[runId] }
                .filterNotNull()

        override suspend fun cancelHomeRun(runId: String) {
            withContext(NonCancellable) {
                runJobs.cancel(runId) {
                    stageExecutor.cancelRunAndSetTerminalStatus(
                        runId = runId,
                        progressState = progressState,
                        updateRunStatus = ::updateRunStatus,
                        beforeTerminalStatus = { packetCaptureCoordinator.settle(runId) },
                    )
                }
            }
        }

        override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome {
            completedRuns[runId]?.let { return it }
            return observeHomeRun(runId)
                .map(DiagnosticsHomeCompositeProgress::outcomeOrThrowIfTerminal)
                .filterNotNull()
                .first()
        }

        override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? =
            completedRuns[runId]
                ?: homeRunPersistence.getCompletedRun(runId)?.also { restored ->
                    completedRuns[runId] = restored
                }

        override suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome? =
            probeResultCache.lookup(fingerprintHash)

        override suspend fun evictCachedOutcome(fingerprintHash: String) = probeResultCache.evict(fingerprintHash)

        override suspend fun startQuickAnalysis(
            options: DiagnosticsHomeRunOptions,
        ): DiagnosticsHomeCompositeRunStarted {
            val runId = UUID.randomUUID().toString()
            progressState.update { current ->
                current +
                    (
                        runId to
                            DiagnosticsHomeCompositeProgress(
                                runId = runId,
                                fingerprintHash = diagnosticsHomeWorkflowService.currentFingerprintHash(),
                                stages =
                                    QuickScanStageSpecs.map { spec ->
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = spec.key,
                                            stageLabel = spec.label,
                                            profileId = spec.profileId,
                                            pathMode = spec.pathMode,
                                            evidenceType = spec.evidenceType,
                                            vantage = spec.vantage,
                                            targetReachability = spec.targetReachability,
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "${spec.label} pending",
                                            summary = "Waiting to run.",
                                        )
                                    },
                            )
                    )
            }
            admitAndLaunchRun(runId, options) {
                val quickScanRunner =
                    DiagnosticsQuickScanRunner(
                        scanRecordStore,
                        diagnosticsHomeWorkflowService,
                        json,
                        activeProbeSafetyPolicy,
                    )
                quickScanRunner.execute(
                    runId = runId,
                    executeStage = { rId, idx, spec, quickScan, maxCandidates ->
                        stageExecutor.executeStageWithTimeout(
                            runId = rId,
                            stageIndex = idx,
                            spec = spec,
                            progressState = progressState,
                            quickScan = quickScan,
                            maxCandidates = maxCandidates,
                        )
                    },
                    runDetectionStage = detectionStageCoordinator::runStage,
                    runPassiveVpnRouteEvidenceStage = ::runPassiveVpnRouteEvidenceStage,
                    markStageFailure = { rId, idx, headline, summary ->
                        stageExecutor.markStageFailure(progressState, rId, idx, headline, summary)
                    },
                    updateStage = { rId, idx, transform ->
                        stageExecutor.updateStage(progressState, rId, idx, transform)
                    },
                    isAuditRunning = {
                        progressState.value[runId]
                            ?.stages
                            ?.getOrNull(0)
                            ?.status ==
                            DiagnosticsHomeCompositeStageStatus.RUNNING
                    },
                    finalizeRun = ::finalizeRun,
                )
            }
            return DiagnosticsHomeCompositeRunStarted(runId = runId)
        }

        private suspend fun executeRun(runId: String) {
            log.i { "started runId=$runId stages=${HomeCompositeStageSpecs.size}" }
            val auditSpec = HomeCompositeStageSpecs[0]
            val auditIndex = 0
            val networkEvents = mutableListOf<NetworkHandoverEvent>()
            val eventCollector =
                scope.launch {
                    networkHandoverMonitor.events.collect { event ->
                        if (event.isActionable) networkEvents += event
                    }
                }
            runJobs.trackChild(runId, eventCollector)

            val auditCompletedSession =
                stageExecutor.executeStageWithTimeout(
                    runId,
                    auditIndex,
                    auditSpec,
                    progressState,
                )
            if (auditCompletedSession == null) {
                val currentStageStatus =
                    progressState.value[runId]
                        ?.stages
                        ?.getOrNull(auditIndex)
                        ?.status
                if (currentStageStatus == DiagnosticsHomeCompositeStageStatus.RUNNING) {
                    stageExecutor.markStageFailure(
                        progressState = progressState,
                        runId = runId,
                        stageIndex = auditIndex,
                        headline = "${auditSpec.label} timed out",
                        summary = "The audit stage did not complete within the allowed time.",
                    )
                }
                skipRemainingStages(runId, reason = "Skipped due to audit stage failure.")
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

            runMiddleStages(runId)

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

        private fun launchRun(
            runId: String,
            block: suspend () -> Unit,
        ) {
            val launched =
                runJobs.launch(
                    runId = runId,
                    onFailure = { error ->
                        try {
                            stageExecutor.cancelRunStages(runId, progressState)
                        } finally {
                            packetCaptureCoordinator.settle(runId)
                            val status =
                                if (error is HomeRawPathSupersededByUserStopException) {
                                    log.i {
                                        "run stopped after user superseded raw-path restoration: runId=$runId"
                                    }
                                    DiagnosticsHomeCompositeRunStatus.CANCELLED
                                } else {
                                    log.e(error) { "run failed: runId=$runId" }
                                    DiagnosticsHomeCompositeRunStatus.FAILED
                                }
                            updateRunStatus(runId, status)
                        }
                    },
                    block = block,
                )
            if (!launched) {
                runDetectionResults.remove(runId)
                progressState.update { current -> current - runId }
                throw DiagnosticsScanStartRejectedException(DiagnosticsScanStartRejectionReason.ScanAlreadyActive)
            }
        }

        private suspend fun admitAndLaunchRun(
            runId: String,
            options: DiagnosticsHomeRunOptions,
            block: suspend () -> Unit,
        ) {
            try {
                packetCaptureCoordinator.admit(runId, options)
                launchRun(runId, block)
            } catch (error: CancellationException) {
                clearRejectedRun(runId)
                throw error
            } catch (error: DiagnosticsScanStartRejectedException) {
                clearRejectedRun(runId)
                throw error
            }
        }

        private suspend fun clearRejectedRun(runId: String) {
            packetCaptureCoordinator.settle(runId)
            runDetectionResults.remove(runId)
            packetCaptureCoordinator.clear(runId)
            progressState.update { current -> current - runId }
        }

        private fun updateRunStatus(
            runId: String,
            status: DiagnosticsHomeCompositeRunStatus,
        ) {
            runDetectionResults.remove(runId)
            packetCaptureCoordinator.clear(runId)
            progressState.update { current ->
                current.updatedRun(runId) { progress ->
                    if (progress.status == DiagnosticsHomeCompositeRunStatus.RUNNING) {
                        progress.copy(
                            status = status,
                            activeStageIndex = null,
                            activeSessionId = null,
                        )
                    } else {
                        progress
                    }
                }
            }
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
            val completedSummary =
                buildCompletedStageSummary(
                    spec = auditSpec,
                    sessionId = auditSessionId,
                    session = auditSession,
                    scanRecordStore = scanRecordStore,
                    json = json,
                )
            var auditOutcome =
                DiagnosticsHomeAuditOutcome(
                    sessionId = completedSummary.sessionId.orEmpty(),
                    fingerprintHash = diagnosticsHomeWorkflowService.currentFingerprintHash(),
                    actionable = completedSummary.recommendationContributor,
                    headline = completedSummary.headline,
                    summary = completedSummary.summary,
                )
            stageExecutor.updateStage(progressState, runId, auditIndex) { completedSummary }
            if (completedSummary.status != DiagnosticsHomeCompositeStageStatus.COMPLETED) {
                skipRemainingStages(runId, reason = "Skipped due to network unavailability.")
                return null
            }
            val finalizedAuditOutcome = diagnosticsHomeWorkflowService.finalizeHomeAudit(auditSessionId)
            auditOutcome = finalizedAuditOutcome
            log.i { "audit finalized actionable=${finalizedAuditOutcome.actionable}" }
            stageExecutor.updateStage(progressState, runId, auditIndex) { current ->
                current.copy(
                    headline = finalizedAuditOutcome.headline,
                    summary = finalizedAuditOutcome.summary,
                    recommendationContributor = finalizedAuditOutcome.actionable,
                )
            }
            return auditOutcome
        }

        /** Runs raw-path middle stages serially, then performs the targeted in-path comparison leg. */
        private suspend fun runMiddleStages(runId: String) {
            HomeCompositeStageSpecs
                .drop(1)
                .dropLast(1)
                .filter(HomeCompositeStageSpec::startsScanSession)
                .filterNot { it.key == "path_comparison" }
                .forEach { spec ->
                    val stageIndex = HomeCompositeStageSpecs.indexOf(spec)
                    if (spec.kind == HomeCompositeStageKind.DETECTION_SIGNALS) {
                        detectionStageCoordinator.runStage(runId, stageIndex, spec)
                        return@forEach
                    }
                    val result =
                        executeProfileStageWithRetry(
                            runId = runId,
                            stageIndex = stageIndex,
                            spec = spec,
                        )
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
                        stageExecutor.updateStage(progressState, runId, stageIndex) { completedSummary }
                    }
                }
            val pathComparisonIndex = HomeCompositeStageSpecs.indexOfFirst { it.key == "path_comparison" }
            if (pathComparisonIndex >= 0) {
                runPathComparisonStage(runId, pathComparisonIndex, HomeCompositeStageSpecs[pathComparisonIndex])
            }
            val passiveVpnEvidenceIndex = HomeCompositeStageSpecs.indexOfFirst { it.key == "vpn_route_evidence" }
            if (passiveVpnEvidenceIndex >= 0) {
                runPassiveVpnRouteEvidenceStage(
                    runId = runId,
                    stageIndex = passiveVpnEvidenceIndex,
                    spec = HomeCompositeStageSpecs[passiveVpnEvidenceIndex],
                )
            }
        }

        private fun runPassiveVpnRouteEvidenceStage(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ) {
            val currentGeneration = runCatching(vpnRouteEvidenceProvider::currentRouteGeneration).getOrNull()
            val routeEvidence = runCatching(vpnRouteEvidenceProvider::capture).getOrDefault(VpnRouteEvidence())
            val evidence =
                passiveVpnRouteEvidenceBuilder.build(
                    runId = runId,
                    serviceStatus = serviceStateStore.status.value,
                    currentGeneration = currentGeneration,
                    vpnRouteEvidence = routeEvidence,
                )
            val captured = evidence.disposition == PassiveVpnRouteEvidenceDisposition.CAPTURED
            stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                current.copy(
                    sessionId = null,
                    status =
                        if (captured) {
                            DiagnosticsHomeCompositeStageStatus.COMPLETED
                        } else {
                            DiagnosticsHomeCompositeStageStatus.SKIPPED
                        },
                    headline = "${spec.label} ${if (captured) "captured" else "skipped"}",
                    summary = passiveVpnRouteEvidenceSummary(evidence),
                    passiveVpnRouteEvidence = evidence.pathValidation,
                )
            }
        }

        private suspend fun runPathComparisonStage(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ) {
            if (!stageExecutor.requireInPathRuntime(progressState, runId, stageIndex, spec)) return
            val selection =
                buildPathComparisonSelection(
                    runId = runId,
                    progressState = progressState,
                    diagnosticsProfileCatalog = diagnosticsProfileCatalog,
                    scanRecordStore = scanRecordStore,
                    json = json,
                    serviceStateStore = serviceStateStore,
                )
            if (selection == null) {
                stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                    current.copy(
                        status = DiagnosticsHomeCompositeStageStatus.SKIPPED,
                        headline = "${spec.label} skipped",
                        summary = "Raw-path evidence did not justify a focused in-path confirmation leg.",
                    )
                }
                return
            }
            stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                current.copy(
                    summary =
                        "Comparing ${selection.domainTargets.size} domain controls/failures and " +
                            "${selection.serviceTargets.size + selection.circumventionTargets.size}" +
                            " service targets in path.",
                )
            }
            val result =
                executeProfileStageWithRetry(
                    runId = runId,
                    stageIndex = stageIndex,
                    spec = spec,
                    targetOverrides =
                        DiagnosticsScanTargetOverrides(
                            domainTargets = selection.domainTargets,
                            serviceTargets = selection.serviceTargets,
                            circumventionTargets = selection.circumventionTargets,
                        ),
                )
            if (result != null) {
                val (sessionId, session) = result
                val completedSummary =
                    buildCompletedStageSummary(
                        spec = spec,
                        sessionId = sessionId,
                        session = session,
                        scanRecordStore = scanRecordStore,
                        json = json,
                    )
                stageExecutor.updateStage(progressState, runId, stageIndex) {
                    completedSummary.copy(
                        summary =
                            completedSummary.summary +
                                " Controls=${selection.domainTargets.filter(
                                    DomainTarget::isControl,
                                ).map(DomainTarget::host)}" +
                                " failed=${selection.failedTargetLabels}",
                    )
                }
            }
        }

        private suspend fun executeProfileStageWithRetry(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
        ): Pair<String, DiagnosticScanSession>? {
            var result =
                stageExecutor.executeStageWithTimeout(
                    runId = runId,
                    stageIndex = stageIndex,
                    spec = spec,
                    progressState = progressState,
                    targetOverrides = targetOverrides,
                )
            if (spec.key != "path_comparison") {
                var retryCount = activeProbeSafetyPolicy.stageRetryBudget
                while (result == null && retryCount > 0) {
                    retryCount -= 1
                    delay(activeProbeSafetyPolicy.stageRetryDelayMs)
                    result =
                        stageExecutor.executeStageWithTimeout(
                            runId = runId,
                            stageIndex = stageIndex,
                            spec = spec,
                            progressState = progressState,
                            targetOverrides = targetOverrides,
                        )
                }
            }
            return result
        }

        private fun skipRemainingStages(
            runId: String,
            reason: String,
        ) {
            HomeCompositeStageSpecs.drop(1).forEachIndexed { i, spec ->
                val stageIndex = i + 1
                log.i { "stage ${spec.key} skipped: $reason" }
                stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                    current.copy(
                        status = DiagnosticsHomeCompositeStageStatus.SKIPPED,
                        headline = "${spec.label} skipped",
                        summary = reason,
                    )
                }
            }
        }

        private suspend fun runDpiStrategyStage(
            runId: String,
            currentAuditOutcome: DiagnosticsHomeAuditOutcome?,
        ): DiagnosticsHomeAuditOutcome? {
            var auditOutcome = currentAuditOutcome
            val dpiStrategySpec = HomeCompositeStageSpecs.last()
            val dpiStrategyIndex = HomeCompositeStageSpecs.lastIndex
            val dpiStrategyResult =
                executeProfileStageWithRetry(
                    runId = runId,
                    stageIndex = dpiStrategyIndex,
                    spec = dpiStrategySpec,
                )
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
                stageExecutor.updateStage(progressState, runId, dpiStrategyIndex) { dpiStrategySummary }
                if (auditOutcome?.actionable != true && dpiStrategySummary.isCompletedStage()) {
                    val strategyAuditOutcome =
                        diagnosticsHomeWorkflowService.finalizeHomeAudit(dpiStrategySessionId)
                    if (strategyAuditOutcome.actionable) {
                        auditOutcome = strategyAuditOutcome
                        stageExecutor.updateStage(progressState, runId, dpiStrategyIndex) { current ->
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

        private suspend fun finalizeRun(
            runId: String,
            auditOutcome: DiagnosticsHomeAuditOutcome?,
            coverageNote: String?,
            dnsIssuesDetected: Boolean,
            networkChanged: Boolean,
        ) {
            packetCaptureCoordinator.settle(runId)
            val detectionResult = runDetectionResults.remove(runId)
            val previousOutcome = completedRuns.mostRecentCompletedRunBefore(runId)
            outcomeFinalizer.finalize(
                HomeCompositeFinalizationRequest(
                    runId = runId,
                    auditOutcome = auditOutcome,
                    coverageNote = coverageNote,
                    dnsIssuesDetected = dnsIssuesDetected,
                    networkChanged = networkChanged,
                    detectionResult = detectionResult,
                    previousOutcome = previousOutcome,
                    packetCaptureDisposition = packetCaptureCoordinator.disposition(runId),
                ),
            )
            packetCaptureCoordinator.clear(runId)
        }
    }

internal object UnavailableVpnRouteEvidenceProvider : VpnRouteEvidenceProvider {
    override val changes = MutableStateFlow(0L)

    override fun capture(): VpnRouteEvidence = VpnRouteEvidence()
}

private fun passiveVpnRouteEvidenceSummary(evidence: PassiveVpnRouteEvidence): String =
    when (evidence.disposition) {
        PassiveVpnRouteEvidenceDisposition.CAPTURED -> {
            "Captured sessionless VPN route evidence: route=${evidence.routeAxis} " +
                "forwarding=${evidence.forwardingAxis} target_reachability=unverified."
        }

        PassiveVpnRouteEvidenceDisposition.NOT_VPN_MODE -> {
            "Skipped because the active runtime is not VPN mode."
        }

        PassiveVpnRouteEvidenceDisposition.STALE_GENERATION -> {
            "Skipped because VPN route evidence did not match the current generation."
        }

        PassiveVpnRouteEvidenceDisposition.INCOMPLETE_EVIDENCE -> {
            "Skipped because current VPN route evidence is incomplete or not owner-verified."
        }

        PassiveVpnRouteEvidenceDisposition.INELIGIBLE_LIFECYCLE -> {
            "Skipped because the VPN route lifecycle is not active."
        }

        PassiveVpnRouteEvidenceDisposition.STALE_FORWARDING_GENERATION -> {
            "Skipped because VPN forwarding evidence belongs to a different generation."
        }
    }
