package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
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
    )

private fun stageTimeoutMs(spec: HomeCompositeStageSpec): Long =
    when (spec.profileId) {
        "ru-dpi-full" -> DpiFullStageTimeoutMs
        else -> DefaultStageTimeoutMs
    }

@Singleton
class DefaultDiagnosticsHomeCompositeRunService
    @Inject
    constructor(
        private val diagnosticsScanController: DiagnosticsScanController,
        private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        @param:Named("diagnosticsJson")
        private val json: Json,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsHomeCompositeRunService {
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
            var auditOutcome: DiagnosticsHomeAuditOutcome? = null
            val auditSpec = HomeCompositeStageSpecs[0]
            val auditIndex = 0
            val networkEvents = mutableListOf<com.poyka.ripdpi.services.NetworkHandoverEvent>()
            val eventCollector =
                scope.launch {
                    networkHandoverMonitor.events.collect { event ->
                        if (event.isActionable) networkEvents += event
                    }
                }

            val auditCompletedSession = executeStageWithTimeout(runId, auditIndex, auditSpec)

            if (auditCompletedSession != null) {
                val auditSessionId = auditCompletedSession.first
                val completedSession = auditCompletedSession.second
                val completedSummary =
                    buildCompletedStageSummary(
                        spec = auditSpec,
                        sessionId = auditSessionId,
                        session = completedSession,
                    ).also { summary ->
                        auditOutcome =
                            DiagnosticsHomeAuditOutcome(
                                sessionId = summary.sessionId.orEmpty(),
                                fingerprintHash = diagnosticsHomeWorkflowService.currentFingerprintHash(),
                                actionable = summary.recommendationContributor,
                                headline = summary.headline,
                                summary = summary.summary,
                            )
                    }
                updateStage(runId, auditIndex) { completedSummary }
                if (completedSession.status == "completed") {
                    auditOutcome = diagnosticsHomeWorkflowService.finalizeHomeAudit(auditSessionId)
                    updateStage(runId, auditIndex) { current ->
                        current.copy(
                            headline = auditOutcome.headline,
                            summary = auditOutcome.summary,
                            recommendationContributor = auditOutcome.actionable,
                        )
                    }
                } else {
                    HomeCompositeStageSpecs.drop(1).forEachIndexed { i, spec ->
                        val stageIndex = i + 1
                        updateStage(runId, stageIndex) { current ->
                            current.copy(
                                status = DiagnosticsHomeCompositeStageStatus.SKIPPED,
                                headline = "${spec.label} skipped",
                                summary = "Skipped due to network unavailability.",
                            )
                        }
                    }
                    eventCollector.cancel()
                    val outcome =
                        buildOutcome(
                            runId = runId,
                            auditOutcome = auditOutcome,
                            coverageNote = null,
                            dnsIssuesDetected = false,
                            networkChanged = false,
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
                    return
                }
            }

            coroutineScope {
                HomeCompositeStageSpecs.drop(1).forEachIndexed { i, spec ->
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
                                    spec = spec,
                                    sessionId = sessionId,
                                    session = completedSession,
                                )
                            updateStage(runId, stageIndex) { completedSummary }
                        }
                    }
                }
            }

            eventCollector.cancel()
            val networkChangedDuringRun = networkEvents.isNotEmpty()
            val coverageNote = crossValidateStrategy(runId)
            val dnsIssuesDetected = detectDnsIssues(runId)
            val outcome =
                buildOutcome(
                    runId = runId,
                    auditOutcome = auditOutcome,
                    coverageNote = coverageNote,
                    dnsIssuesDetected = dnsIssuesDetected,
                    networkChanged = networkChangedDuringRun,
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
            markStageRunning(
                runId = runId,
                stageIndex = stageIndex,
                headline = "${spec.label} running",
                summary = "Starting ${spec.label.lowercase()}.",
            )
            val stageSessionId =
                runCatching {
                    diagnosticsScanController.startScan(
                        pathMode = spec.pathMode,
                        selectedProfileId = spec.profileId,
                        skipActiveScanCheck = true,
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
            if (stageSessionId == null) return null
            updateStage(runId, stageIndex) { current ->
                current.copy(
                    sessionId = stageSessionId,
                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                    headline = "${spec.label} running",
                    summary = "Collecting diagnostics for ${spec.label.lowercase()}.",
                )
            }
            val completedSession =
                diagnosticsTimelineSource.sessions
                    .map { sessions ->
                        sessions.firstOrNull { it.id == stageSessionId && it.status != "running" }
                    }.filterNotNull()
                    .first()
            return stageSessionId to completedSession
        }

        private suspend fun executeStageWithTimeout(
            runId: String,
            stageIndex: Int,
            spec: HomeCompositeStageSpec,
        ): Pair<String, DiagnosticScanSession>? =
            withTimeoutOrNull(stageTimeoutMs(spec)) {
                executeStage(runId, stageIndex, spec)
            }

        private suspend fun buildCompletedStageSummary(
            spec: HomeCompositeStageSpec,
            sessionId: String,
            session: DiagnosticScanSession,
        ): DiagnosticsHomeCompositeStageSummary {
            val persistedSession = scanRecordStore.getScanSession(sessionId)
            val report =
                persistedSession
                    ?.reportJson
                    ?.takeIf(String::isNotBlank)
                    ?.let { reportJson -> DiagnosticsSessionQueries.decodeScanReport(json, reportJson) }
            val status =
                if (session.status == "completed") {
                    DiagnosticsHomeCompositeStageStatus.COMPLETED
                } else {
                    DiagnosticsHomeCompositeStageStatus.FAILED
                }
            val summary = report?.summary?.ifBlank { session.summary } ?: session.summary
            val headline =
                when {
                    status == DiagnosticsHomeCompositeStageStatus.FAILED -> "${spec.label} failed"
                    report?.diagnoses?.isNotEmpty() == true -> spec.label
                    else -> "${spec.label} complete"
                }
            return DiagnosticsHomeCompositeStageSummary(
                stageKey = spec.key,
                stageLabel = spec.label,
                profileId = spec.profileId,
                pathMode = spec.pathMode,
                sessionId = sessionId,
                status = status,
                headline = headline,
                summary = summary,
                recommendationContributor = false,
            )
        }

        private fun buildOutcome(
            runId: String,
            auditOutcome: DiagnosticsHomeAuditOutcome?,
            coverageNote: String?,
            dnsIssuesDetected: Boolean,
            networkChanged: Boolean,
        ): DiagnosticsHomeCompositeOutcome {
            val progress = requireNotNull(progressState.value[runId]) { "Unknown Home diagnostics run '$runId'" }
            val stageSummaries = progress.stages
            val completedStageCount =
                stageSummaries.count { it.status == DiagnosticsHomeCompositeStageStatus.COMPLETED }
            val failedStageCount =
                stageSummaries.count {
                    it.status == DiagnosticsHomeCompositeStageStatus.FAILED ||
                        it.status == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE
                }
            val skippedStageCount = stageSummaries.count { it.status == DiagnosticsHomeCompositeStageStatus.SKIPPED }
            val actionable = auditOutcome?.actionable == true
            val fingerprintHash = auditOutcome?.fingerprintHash ?: progress.fingerprintHash
            val outcomeSummary =
                buildString {
                    val base =
                        auditOutcome?.summary?.takeIf { it.isNotBlank() }
                            ?: run {
                                append("Completed ")
                                append(completedStageCount)
                                append(" of ")
                                append(stageSummaries.size)
                                append(" diagnostics stages.")
                                if (failedStageCount > 0) {
                                    append(' ')
                                    append(failedStageCount)
                                    append(" stage")
                                    if (failedStageCount != 1) {
                                        append('s')
                                    }
                                    append(" finished with failures or were unavailable.")
                                }
                                null
                            }
                    if (base != null) append(base)
                    if (coverageNote != null) {
                        if (isNotEmpty()) append(' ')
                        append(coverageNote)
                    }
                    if (dnsIssuesDetected) {
                        if (isNotEmpty()) append(' ')
                        append("DNS issues were detected during the audit.")
                    }
                    if (networkChanged) {
                        if (isNotEmpty()) append(' ')
                        append("Network changed during analysis \u2014 results may not reflect current network.")
                    }
                }
            return DiagnosticsHomeCompositeOutcome(
                runId = runId,
                fingerprintHash = fingerprintHash,
                actionable = actionable,
                headline =
                    when {
                        actionable -> {
                            "Analysis complete and settings applied"
                        }

                        failedStageCount > 0 -> {
                            "Analysis finished \u2014 $failedStageCount of ${stageSummaries.size} stages failed"
                        }

                        else -> {
                            "Analysis complete"
                        }
                    },
                summary = outcomeSummary,
                recommendationSummary = auditOutcome?.recommendationSummary,
                confidenceSummary = auditOutcome?.confidenceSummary,
                coverageSummary = auditOutcome?.coverageSummary,
                appliedSettings = auditOutcome?.appliedSettings.orEmpty(),
                recommendedSessionId = auditOutcome?.takeIf { it.actionable }?.sessionId,
                stageSummaries = stageSummaries,
                completedStageCount = completedStageCount,
                failedStageCount = failedStageCount,
                skippedStageCount = skippedStageCount,
                bundleSessionIds = stageSummaries.mapNotNull { it.sessionId },
            )
        }

        private suspend fun crossValidateStrategy(runId: String): String? {
            val auditSpec = HomeCompositeStageSpecs[0]
            val dpiFullSpec = HomeCompositeStageSpecs[2]
            val stages = progressState.value[runId]?.stages ?: return null
            val auditSessionId =
                stages.firstOrNull { it.stageKey == auditSpec.key }?.sessionId
            val dpiFullSessionId =
                stages.firstOrNull { it.stageKey == dpiFullSpec.key }?.sessionId
            val auditReport =
                auditSessionId?.let { id ->
                    scanRecordStore
                        .getScanSession(id)
                        ?.reportJson
                        ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                }
            val dpiFullReport =
                dpiFullSessionId?.let { id ->
                    scanRecordStore
                        .getScanSession(id)
                        ?.reportJson
                        ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                }
            val auditProbeHosts =
                auditReport
                    ?.strategyProbeReport
                    ?.targetSelection
                    ?.domainHosts
                    ?.toSet()
                    ?: emptySet()
            val coverageGapCount =
                dpiFullReport
                    ?.observations
                    ?.filter { obs ->
                        obs.kind == ObservationKind.DOMAIN &&
                            obs.domain != null &&
                            obs.target !in auditProbeHosts &&
                            (
                                obs.domain.transportFailure != TransportFailureKind.NONE ||
                                    obs.domain.httpStatus == HttpProbeStatus.UNREACHABLE
                            )
                    }?.size ?: 0
            val suffix = if (coverageGapCount != 1) "s" else ""
            val message =
                "$coverageGapCount additional domain$suffix" +
                    " showed connectivity issues not covered by the strategy probe."
            return if (coverageGapCount > 0) message else null
        }

        private suspend fun detectDnsIssues(runId: String): Boolean {
            val auditSpec = HomeCompositeStageSpecs[0]
            val auditSessionId =
                progressState.value[runId]
                    ?.stages
                    ?.firstOrNull { it.stageKey == auditSpec.key }
                    ?.sessionId
                    ?: return false
            val auditReport =
                scanRecordStore
                    .getScanSession(auditSessionId)
                    ?.reportJson
                    ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
            return auditReport?.resolverRecommendation != null
        }

        private fun markStageRunning(
            runId: String,
            stageIndex: Int,
            headline: String,
            summary: String,
        ) {
            progressState.update { current ->
                current.updatedRun(runId) { progress ->
                    progress.copy(
                        activeStageIndex = stageIndex,
                        activeSessionId = progress.stages.getOrNull(stageIndex)?.sessionId,
                        stages =
                            progress.stages.updated(stageIndex) { stage ->
                                stage.copy(
                                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                                    headline = headline,
                                    summary = summary,
                                )
                            },
                    )
                }
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

        private fun <T> List<T>.updated(
            index: Int,
            transform: (T) -> T,
        ): List<T> =
            mapIndexed { currentIndex, item ->
                if (currentIndex == index) {
                    transform(item)
                } else {
                    item
                }
            }

        private fun Map<String, DiagnosticsHomeCompositeProgress>.updatedRun(
            runId: String,
            transform: (DiagnosticsHomeCompositeProgress) -> DiagnosticsHomeCompositeProgress,
        ): Map<String, DiagnosticsHomeCompositeProgress> {
            val progress = getValue(runId)
            return this + (runId to transform(progress))
        }
    }
