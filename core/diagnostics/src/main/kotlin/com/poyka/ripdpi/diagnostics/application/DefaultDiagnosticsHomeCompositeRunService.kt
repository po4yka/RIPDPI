@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

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
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val comparisonScanCoordinator: ComparisonScanCoordinator,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        private val serviceStateStore: ServiceStateStore,
        private val probeResultCache: ProbeResultCache,
        private val stageExecutor: HomeCompositeStageExecutor,
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
        private val runDetectionResults = ConcurrentHashMap<String, HomeDetectionStageOutcome>()
        private val runJobs = HomeCompositeRunJobs(scope)
        private val activeProbeSafetyPolicy = ActiveProbeSafetyPolicy()

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
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "${spec.label} pending",
                                            summary = "Waiting to run.",
                                        )
                                    },
                            )
                    )
            }
            launchRun(runId) { executeRun(runId) }
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

        override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = completedRuns[runId]

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
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "${spec.label} pending",
                                            summary = "Waiting to run.",
                                        )
                                    },
                            )
                    )
            }
            launchRun(runId) {
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
                    runDetectionStage = ::runDetectionStage,
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

        private fun launchRun(
            runId: String,
            block: suspend () -> Unit,
        ) {
            val launched =
                runJobs.launch(
                    runId = runId,
                    onFailure = { error ->
                        log.e(error) { "run failed: runId=$runId" }
                        try {
                            stageExecutor.cancelRunStages(runId, progressState)
                        } finally {
                            updateRunStatus(runId, DiagnosticsHomeCompositeRunStatus.FAILED)
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

        private fun updateRunStatus(
            runId: String,
            status: DiagnosticsHomeCompositeRunStatus,
        ) {
            runDetectionResults.remove(runId)
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

        /** Runs raw-path middle stages in parallel, then performs the targeted in-path comparison leg. */
        private suspend fun runParallelMiddleStages(runId: String) {
            coroutineScope {
                HomeCompositeStageSpecs
                    .drop(1)
                    .dropLast(1)
                    .filterNot { it.key == "path_comparison" }
                    .forEach { spec ->
                        val stageIndex = HomeCompositeStageSpecs.indexOf(spec)
                        launch {
                            if (spec.kind == HomeCompositeStageKind.DETECTION_SIGNALS) {
                                runDetectionStage(runId, stageIndex, spec)
                                return@launch
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
                    }
            }
            val pathComparisonIndex = HomeCompositeStageSpecs.indexOfFirst { it.key == "path_comparison" }
            if (pathComparisonIndex >= 0) {
                runPathComparisonStage(runId, pathComparisonIndex, HomeCompositeStageSpecs[pathComparisonIndex])
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
            if (spec.key == "path_comparison") {
                return result
            }
            repeat(activeProbeSafetyPolicy.stageRetryBudget) {
                if (result != null) return result
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
            return result
        }

        private suspend fun runDetectionStage(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ) {
            stageExecutor.updateStage(progressState, runId, stageIndex) { stage ->
                stage.copy(
                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                    headline = "${spec.label} running",
                    summary = "Starting ${spec.label.lowercase()}.",
                )
            }
            log.i { "stage ${spec.key} started (detection-runner)" }
            val outcome =
                runCatching {
                    withTimeoutOrNull(DetectionStageTimeoutMs) {
                        detectionStageRunner.run { label, detail ->
                            stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                                current.copy(summary = "$label: $detail")
                            }
                        }
                    }
                }.getOrNull()
            if (outcome == null) {
                log.w { "detection stage failed or timed out" }
                stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                    current.copy(
                        status = DiagnosticsHomeCompositeStageStatus.FAILED,
                        headline = "${spec.label} failed",
                        summary = "Detection checks did not complete within the allowed time.",
                    )
                }
                return
            }
            runDetectionResults[runId] = outcome
            val verdictText =
                when (outcome.verdict) {
                    DiagnosticsHomeDetectionVerdict.DETECTED -> "Detection signals were observed"
                    DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW -> "Detection results need review"
                    DiagnosticsHomeDetectionVerdict.NOT_DETECTED -> "No detection signals observed"
                }
            val summaryLine =
                if (outcome.detectedSignalCount > 0) {
                    "$verdictText. ${outcome.detectedSignalCount} detection signal" +
                        "${if (outcome.detectedSignalCount == 1) "" else "s"} observed."
                } else {
                    "$verdictText."
                }
            stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                current.copy(
                    status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                    headline = "${spec.label} complete",
                    summary = summaryLine,
                )
            }
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

        @Suppress("detekt.LongMethod")
        private suspend fun finalizeRun(
            runId: String,
            auditOutcome: DiagnosticsHomeAuditOutcome?,
            coverageNote: String?,
            dnsIssuesDetected: Boolean,
            networkChanged: Boolean,
        ) {
            val detectionResult = runDetectionResults.remove(runId)
            val previousOutcome = completedRuns.mostRecentCompletedRunBefore(runId)
            val catalogSnapshot =
                withContext(Dispatchers.Default) {
                    runCatching { detectorCatalogSource.snapshot() }.getOrDefault(HomeDetectorCatalogSnapshot())
                }
            val networkCharacter =
                runCatching { analysisAugmentationSource.networkCharacter() }.getOrNull()
            val routingSanity =
                runCatching { analysisAugmentationSource.routingSanity() }.getOrNull()
            val bufferbloat =
                runCatching { analysisAugmentationSource.bufferbloat() }.getOrNull()
            val dnsCharacterization =
                runCatching { analysisAugmentationSource.dnsCharacterization() }.getOrNull()
            val baseOutcome =
                withContext(Dispatchers.Default) {
                    buildHomeCompositeOutcome(
                        runId,
                        auditOutcome,
                        coverageNote,
                        dnsIssuesDetected,
                        networkChanged,
                        progressState,
                    )
                }
            val effectivenessLedger =
                baseOutcome.fingerprintHash
                    ?.let { fingerprint ->
                        runCatching {
                            loadStrategyEffectiveness(
                                networkEdgePreferenceStore = networkEdgePreferenceStore,
                                fingerprintHash = fingerprint,
                            )
                        }.getOrDefault(emptyList())
                    }.orEmpty()
            val outcomeWithoutSynthesis =
                baseOutcome.copy(
                    detectionVerdict = detectionResult?.verdict,
                    detectionFindings = detectionResult?.findings.orEmpty(),
                    detectionRuleApplied = detectionResult?.ruleApplied,
                    detectionEvidenceScopes = detectionResult?.evidenceScopes.orEmpty(),
                    detectionSignalCount = detectionResult?.detectedSignalCount,
                    detectionLocalFindings = detectionResult?.localFindings.orEmpty(),
                    detectionNetworkFindings = detectionResult?.networkFindings.orEmpty(),
                    installedVpnDetectorCount = catalogSnapshot.installedVpnDetectorCount.takeIf { it >= 0 },
                    installedVpnDetectorTopApps = catalogSnapshot.topDetectorPackages,
                    networkCharacter = networkCharacter,
                    strategyEffectiveness = effectivenessLedger,
                    routingSanity = routingSanity,
                    regressionDelta = computeRegressionDelta(baseOutcome, previousOutcome),
                    bufferbloat = bufferbloat,
                    dnsCharacterization = dnsCharacterization,
                    connectivityAssessment =
                        buildConnectivityAssessment(
                            runId = runId,
                            progressState = progressState,
                            scanRecordStore = scanRecordStore,
                            json = json,
                            serviceStateStore = serviceStateStore,
                            comparisonScanCoordinator = comparisonScanCoordinator,
                        ),
                )
            val reproAction =
                outcomeWithoutSynthesis.connectivityAssessment
                    ?.takeIf { it.assessmentCode == ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE }
                    ?.let {
                        HomeReproAction(
                            actionId = "internet_loss_repro",
                            label = "Run internet-loss repro",
                            summary = "Retry a paired raw-path and in-path comparison on complaint-specific targets.",
                        )
                    }
            val (synthHeadline, synthSteps) = synthesizeActionableSummary(outcomeWithoutSynthesis)
            val outcome =
                outcomeWithoutSynthesis.copy(
                    actionableHeadline = synthHeadline,
                    actionableNextSteps = synthSteps,
                    internetLossReproAction = reproAction,
                )
            completedRuns[runId] = outcome
            if (outcome.fingerprintHash != null && outcome.completedStageCount > 0) {
                scope.launch {
                    runCatching {
                        probeResultCache.store(
                            CachedProbeOutcome(
                                fingerprintHash = outcome.fingerprintHash,
                                headline = outcome.headline,
                                summary = outcome.summary,
                                appliedSettings = outcome.appliedSettings,
                                completedStageCount = outcome.completedStageCount,
                                failedStageCount = outcome.failedStageCount,
                                cachedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
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
    }
