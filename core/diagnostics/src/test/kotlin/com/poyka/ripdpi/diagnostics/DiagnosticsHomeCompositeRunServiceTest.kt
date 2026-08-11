package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.testsupport.ControllableNetworkHandoverMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHomeCompositeRunCancellationTest {
    @Test
    fun `cancelHomeRun cancels the engine and publishes a terminal state`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            var cancelCalls = 0
            val cancelStarted = CompletableDeferred<Unit>()
            val allowCancelToFinish = CompletableDeferred<Unit>()
            val scanController =
                object : DiagnosticsScanController {
                    override val hiddenAutomaticProbeActive = MutableStateFlow(false)

                    override suspend fun startScan(
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                    ): DiagnosticsManualScanStartResult = DiagnosticsManualScanStartResult.Started("active-session")

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() {
                        cancelCalls += 1
                        stores.sessionsState.value =
                            stores.sessionsState.value +
                            diagnosticsSession(
                                id = "active-session",
                                profileId = "automatic-audit",
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = "completed during cancellation",
                            )
                        timelineSource.sessions.value =
                            timelineSource.sessions.value +
                            DiagnosticScanSession(
                                id = "active-session",
                                profileId = "automatic-audit",
                                pathMode = ScanPathMode.RAW_PATH.name,
                                serviceMode = "VPN",
                                status = "completed",
                                summary = "completed during cancellation",
                                startedAt = 10L,
                                finishedAt = 20L,
                            )
                        cancelStarted.complete(Unit)
                        allowCancelToFinish.await()
                        yield()
                    }

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-cancel"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        error("cancelled run must not finalize")

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            runCurrent()
            val duplicateStartError = runCatching { service.startHomeAnalysis() }.exceptionOrNull()
            assertTrue(duplicateStartError is DiagnosticsScanStartRejectedException)
            assertEquals(
                DiagnosticsScanStartRejectionReason.ScanAlreadyActive,
                (duplicateStartError as DiagnosticsScanStartRejectedException).reason,
            )
            val cancellationJob = backgroundScope.launch { service.cancelHomeRun(started.runId) }
            cancelStarted.await()
            val teardownStartError = runCatching { service.startHomeAnalysis() }.exceptionOrNull()
            assertTrue(teardownStartError is DiagnosticsScanStartRejectedException)
            assertEquals(
                DiagnosticsHomeCompositeRunStatus.RUNNING,
                service.observeHomeRun(started.runId).first().status,
            )
            allowCancelToFinish.complete(Unit)
            cancellationJob.join()

            val progress = service.observeHomeRun(started.runId).first()
            assertEquals(1, cancelCalls)
            assertEquals(DiagnosticsHomeCompositeRunStatus.CANCELLED, progress.status)
            assertEquals(null, progress.activeStageIndex)
            assertEquals(null, progress.activeSessionId)
            val finalizeError = runCatching { service.finalizeHomeRun(started.runId) }.exceptionOrNull()
            assertTrue(finalizeError is DiagnosticsHomeRunTerminatedException)
            assertEquals(
                DiagnosticsHomeCompositeRunStatus.CANCELLED,
                (finalizeError as DiagnosticsHomeRunTerminatedException).status,
            )
        }

    @Test
    fun `cancelHomeRun fails when partial report retrieval fails`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val scanController =
                object : DiagnosticsScanController {
                    override val hiddenAutomaticProbeActive = MutableStateFlow(false)

                    override suspend fun startScan(
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                    ): DiagnosticsManualScanStartResult = DiagnosticsManualScanStartResult.Started("active-session")

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() =
                        throw java.io.IOException("partial report retrieval failed")

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-cancel-failure"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        error("cancelled run must not finalize")

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            runCurrent()
            val failure = runCatching { service.cancelHomeRun(started.runId) }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertEquals("partial report retrieval failed", failure?.message)
            assertEquals(
                DiagnosticsHomeCompositeRunStatus.FAILED,
                service.observeHomeRun(started.runId).first().status,
            )
        }

    @Test
    fun `cancelRunStages reports a session cancellation failure after cancelling the rest`() =
        runTest {
            val cancelledSessionIds = mutableListOf<String>()
            val controller =
                object : DiagnosticsScanController {
                    override val hiddenAutomaticProbeActive = MutableStateFlow(false)

                    override suspend fun startScan(
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                    ): DiagnosticsManualScanStartResult = error("unused")

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() = error("run-scoped cancellation required")

                    override suspend fun cancelScan(sessionId: String) {
                        cancelledSessionIds += sessionId
                        if (sessionId == "session-one") throw java.io.IOException("partial report retrieval failed")
                    }

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val progressState =
                MutableStateFlow(
                    mapOf(
                        "parallel-run" to
                            DiagnosticsHomeCompositeProgress(
                                runId = "parallel-run",
                                stages =
                                    listOf(
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = "one",
                                            stageLabel = "One",
                                            profileId = "one",
                                            pathMode = ScanPathMode.RAW_PATH,
                                            status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                                            headline = "running",
                                            summary = "running",
                                            sessionId = "session-one",
                                        ),
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = "two",
                                            stageLabel = "Two",
                                            profileId = "two",
                                            pathMode = ScanPathMode.RAW_PATH,
                                            status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                                            headline = "running",
                                            summary = "running",
                                            sessionId = "session-two",
                                        ),
                                    ),
                            ),
                    ),
                )
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = MutableDiagnosticsTimelineSource(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                )

            val failure = runCatching { executor.cancelRunStages("parallel-run", progressState) }.exceptionOrNull()

            assertEquals(listOf("session-one", "session-two"), cancelledSessionIds)
            assertTrue(failure is java.io.IOException)
            assertEquals("partial report retrieval failed", failure?.message)
        }

    @Test
    fun `failed terminal progress cannot wait forever for an outcome`() {
        val error =
            runCatching {
                DiagnosticsHomeCompositeProgress(
                    runId = "failed-run",
                    status = DiagnosticsHomeCompositeRunStatus.FAILED,
                ).outcomeOrThrowIfTerminal()
            }.exceptionOrNull()

        assertTrue(error is DiagnosticsHomeRunTerminatedException)
        assertEquals(DiagnosticsHomeCompositeRunStatus.FAILED, (error as DiagnosticsHomeRunTerminatedException).status)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHomeCompositeStageSerializationTest {
    @Test
    fun `home analysis never overlaps profile scans`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            var activeProfileScans = 0
            var peakActiveProfileScans = 0
            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { pathMode, profileId, sessionId ->
                        activeProfileScans += 1
                        peakActiveProfileScans = maxOf(peakActiveProfileScans, activeProfileScans)
                        backgroundScope.launch {
                            yield()
                            val session =
                                diagnosticsSession(
                                    id = sessionId,
                                    profileId = requireNotNull(profileId),
                                    pathMode = pathMode.name,
                                    summary = "$profileId complete",
                                )
                            stores.sessionsState.value = stores.sessionsState.value + session
                            timelineSource.sessions.value =
                                timelineSource.sessions.value +
                                DiagnosticScanSession(
                                    id = sessionId,
                                    profileId = profileId,
                                    pathMode = pathMode.name,
                                    serviceMode = "VPN",
                                    status = "completed",
                                    summary = "Completed",
                                    startedAt = 10L,
                                    finishedAt = 20L,
                                )
                            activeProfileScans -= 1
                        }
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-serialized"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-serialized",
                            actionable = true,
                            headline = "Analysis complete",
                            summary = "Reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            service.finalizeHomeRun(started.runId)

            assertEquals(1, peakActiveProfileScans)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHomeCompositeRunServiceTest {
    @Test
    fun `startHomeAnalysis runs fixed stage order and keeps actionable audit recommendation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(profileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = "$profileId complete",
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value + diagnosticScanSession(sessionId, profileId, "completed")
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-1"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-1",
                            actionable = true,
                            headline = "Analysis complete and settings applied",
                            summary = "Reusable settings found.",
                            recommendationSummary = "TCP split + QUIC fake",
                            appliedSettings = listOf(DiagnosticsAppliedSetting("TCP/TLS lane", "Split")),
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                            stageTelemetryRecorder = HomeCompositeStageTelemetryRecorder(stores, serviceStateStore),
                            stageCpuTracker = HomeCompositeStageCpuTracker(),
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            // Detection stage (detection_signals) runs via HomeDetectionStageRunner,
            // not DiagnosticsScanController — so startedRequests lists only profile-scan stages.
            assertEquals(
                listOf(
                    ScanPathMode.RAW_PATH to "automatic-audit",
                    ScanPathMode.RAW_PATH to "default",
                    ScanPathMode.RAW_PATH to "ru-throttling",
                    ScanPathMode.RAW_PATH to "ru-circumvention",
                    ScanPathMode.RAW_PATH to "ru-dpi-full",
                    ScanPathMode.RAW_PATH to "ru-dpi-strategy",
                ),
                scanController.startedRequests,
            )
            assertTrue(outcome.actionable)
            assertEquals("scan-1", outcome.recommendedSessionId)
            // Detection stage is FAILED and path comparison is skipped without paired evidence.
            assertEquals(6, outcome.completedStageCount)
            assertEquals(1, outcome.failedStageCount)
            assertEquals(
                outcome.stageSummaries
                    .map { it.stageKey }
                    .toSet(),
                stores.telemetryState.value
                    .mapNotNull { it.diagnosticsStageKey }
                    .toSet(),
            )
        }

    @Test
    fun `stage failure does not abort later home analysis stages`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        val failed = profileId == "default"
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(profileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = if (failed) "$profileId failed" else "$profileId complete",
                                status = if (failed) "failed" else "completed",
                                reportJson = null,
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value +
                            diagnosticScanSession(
                                sessionId = sessionId,
                                profileId = profileId,
                                status = if (failed) "failed" else "completed",
                                summary = session.summary,
                            )
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-2"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-2",
                            actionable = false,
                            headline = "Analysis complete",
                            summary = "No reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(6, scanController.startedRequests.size)
            assertFalse(outcome.actionable)
            // Path comparison is skipped because there is no paired raw-path evidence to compare.
            assertEquals(5, outcome.completedStageCount)
            assertEquals(2, outcome.failedStageCount)
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.FAILED,
                outcome.stageSummaries.first { it.profileId == "default" }.status,
            )
            assertTrue(outcome.bundleSessionIds.contains("scan-3"))
            assertTrue(outcome.bundleSessionIds.contains("scan-4"))
        }

    @Test
    fun `strategy stage timeout reuses partial cancellation results instead of retrying immediately`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val scanController =
                object : DiagnosticsScanController {
                    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
                    val startedRequests = mutableListOf<Pair<ScanPathMode, String?>>()
                    val cancelledSessionIds = mutableListOf<String>()
                    val liveSessionIds = linkedSetOf("sibling-session")
                    private var nextId = 0
                    private var activeStrategySessionId: String? = null

                    override suspend fun startScan(
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                    ): DiagnosticsManualScanStartResult {
                        nextId += 1
                        val sessionId = "scan-$nextId"
                        val profileId = requireNotNull(selectedProfileId)
                        startedRequests += pathMode to profileId
                        val status = if (profileId == "ru-dpi-strategy") "running" else "completed"
                        val summary =
                            if (status == "running") {
                                "$profileId running"
                            } else {
                                "$profileId complete"
                            }
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = profileId,
                                pathMode = pathMode.name,
                                status = status,
                                summary = summary,
                                reportJson = if (status == "running") null else "{}",
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value + diagnosticScanSession(sessionId, profileId, status, summary)
                        if (profileId == "ru-dpi-strategy") {
                            activeStrategySessionId = sessionId
                            liveSessionIds += sessionId
                        }
                        return DiagnosticsManualScanStartResult.Started(sessionId)
                    }

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() = error("timeout cancellation must be session-scoped")

                    override suspend fun cancelScan(sessionId: String) {
                        require(sessionId == activeStrategySessionId)
                        cancelledSessionIds += sessionId
                        liveSessionIds -= sessionId
                        val recoveredSession =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = "ru-dpi-strategy",
                                pathMode = ScanPathMode.RAW_PATH.name,
                                status = "completed",
                                summary = ScanCompletedWithPartialResultsSummary,
                                reportJson = "{}",
                            )
                        stores.sessionsState.value =
                            stores.sessionsState.value.filterNot { it.id == sessionId } + recoveredSession
                        timelineSource.sessions.value =
                            timelineSource.sessions.value.filterNot { it.id == sessionId } +
                            diagnosticScanSession(
                                sessionId,
                                "ru-dpi-strategy",
                                "completed",
                                ScanCompletedWithPartialResultsSummary,
                            )
                        activeStrategySessionId = null
                    }

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-partial"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-partial",
                            actionable = true,
                            headline = "Analysis complete and settings applied",
                            summary = "Reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(1, scanController.startedRequests.count { it.second == "ru-dpi-strategy" })
            assertEquals(listOf("scan-6"), scanController.cancelledSessionIds)
            assertEquals(setOf("sibling-session"), scanController.liveSessionIds)
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.COMPLETED,
                outcome.stageSummaries.first { it.stageKey == "dpi_strategy" }.status,
            )
            assertTrue(outcome.bundleSessionIds.contains("scan-6"))
        }

    @Test
    fun `audit failure skips remaining stages`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        val failed = profileId == "automatic-audit"
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(profileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = if (failed) "$profileId failed" else "$profileId complete",
                                status = if (failed) "failed" else "completed",
                                reportJson = null,
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value +
                            diagnosticScanSession(
                                sessionId = sessionId,
                                profileId = profileId,
                                status = if (failed) "failed" else "completed",
                                summary = session.summary,
                            )
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-3"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        error("should not be called when audit failed")

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            // Only audit stage ran; remaining 7 stages (6 profile + 1 detection) were skipped
            assertEquals(1, scanController.startedRequests.size)
            assertEquals(0, outcome.completedStageCount)
            assertEquals(7, outcome.skippedStageCount)
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.SKIPPED,
                outcome.stageSummaries.first { it.profileId == "default" }.status,
            )
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.SKIPPED,
                outcome.stageSummaries.first { it.profileId == "ru-dpi-full" }.status,
            )
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.SKIPPED,
                outcome.stageSummaries.first { it.profileId == "ru-dpi-strategy" }.status,
            )
        }

    @Test
    fun `network change during run appends warning to summary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val monitor = ControllableNetworkHandoverMonitor()

            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        // Emit a network handover event when the second stage (default) starts
                        if (profileId == "default") {
                            monitor.emit(
                                NetworkHandoverEvent(
                                    previousFingerprint = null,
                                    currentFingerprint =
                                        NetworkFingerprint(
                                            transport = "cellular",
                                            networkValidated = true,
                                            captivePortalDetected = false,
                                            privateDnsMode = "system",
                                            dnsServers = listOf("8.8.8.8"),
                                        ),
                                    classification = "wifi_to_cellular",
                                    occurredAt = 1000L,
                                ),
                            )
                        }
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(profileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = "$profileId complete",
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value + diagnosticScanSession(sessionId, profileId, "completed")
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-net"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-net",
                            actionable = false,
                            headline = "Analysis complete",
                            summary = "No reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = monitor,
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertTrue(
                "Expected summary to mention network change, got: ${outcome.summary}",
                outcome.summary.contains("Network changed during analysis"),
            )
        }

    @Test
    fun `transient stage failure retries once before failing`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val attemptCounts = mutableMapOf<String, Int>()
            val scanController =
                object : DiagnosticsScanController {
                    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
                    val startedRequests = mutableListOf<Pair<ScanPathMode, String?>>()
                    private var nextId = 0

                    override suspend fun startScan(
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                    ): DiagnosticsManualScanStartResult {
                        val count = (attemptCounts[selectedProfileId] ?: 0) + 1
                        attemptCounts[selectedProfileId ?: ""] = count
                        startedRequests += pathMode to selectedProfileId

                        // First attempt at "default" returns RequiresHiddenProbeResolution
                        if (selectedProfileId == "default" && count == 1) {
                            return DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                                requestId = "req-retry",
                                profileName = "default",
                                pathMode = pathMode,
                                scanKind = ScanKind.CONNECTIVITY,
                                isFullAudit = false,
                            )
                        }

                        nextId += 1
                        val sessionId = "scan-$nextId"
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(selectedProfileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = "$selectedProfileId complete",
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value +
                            diagnosticScanSession(sessionId, selectedProfileId, "completed")
                        return DiagnosticsManualScanStartResult.Started(sessionId)
                    }

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() = Unit

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-retry"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-retry",
                            actionable = false,
                            headline = "Analysis complete",
                            summary = "No reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            // Path comparison is skipped without paired raw-path evidence; default still retries once.
            assertEquals(6, outcome.completedStageCount)
            assertEquals(1, outcome.failedStageCount)
            // "default" was attempted twice
            assertEquals(2, attemptCounts["default"])
        }

    @Test
    fun `cross-stage validation detects coverage gaps`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()

            val auditReportJson = encodeScanReport(auditReportWithHosts("youtube.com"))
            val dpiFullReportJson = encodeScanReport(dpiFullReportWithObservations("signal.org"))

            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        val reportJson =
                            when (profileId) {
                                "automatic-audit" -> auditReportJson
                                "ru-dpi-full" -> dpiFullReportJson
                                else -> null
                            }
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(profileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = "$profileId complete",
                                reportJson = reportJson,
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value + diagnosticScanSession(sessionId, profileId, "completed")
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-cov"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-cov",
                            actionable = false,
                            headline = "Analysis complete",
                            summary = "No reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = json,
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertTrue(
                "Expected summary to mention additional domains, got: ${outcome.summary}",
                outcome.summary.contains("additional domain") && outcome.summary.contains("connectivity issues"),
            )
        }

    private fun auditReportWithHosts(vararg hosts: String): ScanReport =
        ScanReport(
            sessionId = "scan-1",
            profileId = "automatic-audit",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "Audit complete",
            strategyProbeReport =
                StrategyProbeReport(
                    suiteId = "quick_v1",
                    recommendation =
                        StrategyProbeRecommendation(
                            tcpCandidateId = "split",
                            tcpCandidateLabel = "Split",
                            quicCandidateId = "fake",
                            quicCandidateLabel = "Fake",
                            rationale = "Best performing candidate",
                            recommendedProxyConfigJson = "{}",
                        ),
                    targetSelection =
                        StrategyProbeTargetSelection(
                            cohortId = "manual-sensitive",
                            cohortLabel = "Manual sensitive",
                            domainHosts = hosts.toList(),
                        ),
                ),
        )

    private fun dpiFullReportWithObservations(vararg failedHosts: String): ScanReport =
        ScanReport(
            sessionId = "scan-3",
            profileId = "ru-dpi-full",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "DPI full complete",
            observations =
                failedHosts.map { host ->
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = host,
                        domain =
                            DomainObservationFact(
                                host = host,
                                transportFailure = TransportFailureKind.RESET,
                            ),
                    )
                },
        )

    private fun encodeScanReport(report: ScanReport): String =
        kotlinx.serialization.json.Json.encodeToString(
            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                .serializer(),
            report.toEngineScanReportWire(),
        )

    @Test
    fun `dns issues detected appends note to summary`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()

            val auditReport =
                ScanReport(
                    sessionId = "scan-1",
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "Audit complete",
                    resolverRecommendation =
                        ResolverRecommendation(
                            triggerOutcome = "tampering_detected",
                            selectedResolverId = "cloudflare",
                            selectedProtocol = "doh",
                            selectedEndpoint = "https://cloudflare-dns.com/dns-query",
                            rationale = "DNS tampering detected",
                        ),
                )

            val auditReportJson =
                kotlinx.serialization.json.Json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                        .serializer(),
                    auditReport.toEngineScanReportWire(),
                )

            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        val reportJson = if (profileId == "automatic-audit") auditReportJson else null
                        val session =
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(profileId),
                                pathMode = ScanPathMode.RAW_PATH.name,
                                summary = "$profileId complete",
                                reportJson = reportJson,
                            )
                        stores.sessionsState.value = stores.sessionsState.value + session
                        timelineSource.sessions.value =
                            timelineSource.sessions.value + diagnosticScanSession(sessionId, profileId, "completed")
                    },
                )
            val workflowService =
                object : DiagnosticsHomeWorkflowService {
                    override suspend fun currentFingerprintHash(): String = "fp-dns"

                    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = sessionId,
                            fingerprintHash = "fp-dns",
                            actionable = false,
                            headline = "Analysis complete",
                            summary = "No reusable settings found.",
                        )

                    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                        error("unused")
                }
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    serviceStateStore = serviceStateStore,
                    probeResultCache = NoOpProbeResultCache(),
                    stageExecutor =
                        HomeCompositeStageExecutor(
                            diagnosticsScanController = scanController,
                            diagnosticsTimelineSource = timelineSource,
                            serviceStateStore = serviceStateStore,
                        ),
                    json = json,
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertTrue(
                "Expected summary to mention DNS issues, got: ${outcome.summary}",
                outcome.summary.contains("DNS issues were detected"),
            )
        }

    private fun diagnosticScanSession(
        sessionId: String,
        profileId: String,
        status: String,
        summary: String = "Completed",
    ): DiagnosticScanSession =
        DiagnosticScanSession(
            id = sessionId,
            profileId = profileId,
            pathMode = ScanPathMode.RAW_PATH.name,
            serviceMode = "VPN",
            status = status,
            summary = summary,
            startedAt = 10L,
            finishedAt = if (status == "completed" || status == "failed") 20L else null,
        )
}

private class NoOpNetworkHandoverMonitor : NetworkHandoverMonitor {
    override val events = MutableSharedFlow<NetworkHandoverEvent>()
}

private class RecordingHomeCompositeScanController(
    private val onStart: suspend (ScanPathMode, String?, String) -> Unit,
) : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
    val startedRequests = mutableListOf<Pair<ScanPathMode, String?>>()
    private var nextId = 0

    override suspend fun startScan(
        pathMode: ScanPathMode,
        selectedProfileId: String?,
        skipActiveScanCheck: Boolean,
        allowSensitiveProfileStart: Boolean,
        scanDeadlineMs: Long?,
        maxCandidates: Int?,
        targetOverrides: DiagnosticsScanTargetOverrides?,
    ): DiagnosticsManualScanStartResult {
        nextId += 1
        val sessionId = "scan-$nextId"
        startedRequests += pathMode to selectedProfileId
        onStart(pathMode, selectedProfileId, sessionId)
        return DiagnosticsManualScanStartResult.Started(sessionId)
    }

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution = error("unused")

    override suspend fun cancelActiveScan() = Unit

    override suspend fun setActiveProfile(profileId: String) = Unit
}

private class NoOpProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun snapshot(): List<CachedProbeOutcome> = emptyList()

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

private class MutableDiagnosticsTimelineSource : DiagnosticsTimelineSource {
    override val activeScanProgress = MutableStateFlow<ScanProgress?>(null)
    override val activeConnectionSession = MutableStateFlow<DiagnosticConnectionSession?>(null)
    override val profiles = MutableStateFlow(emptyList<DiagnosticProfile>())
    override val sessions = MutableStateFlow(emptyList<DiagnosticScanSession>())
    override val approachStats = MutableStateFlow(emptyList<BypassApproachSummary>())
    override val snapshots = MutableStateFlow(emptyList<DiagnosticNetworkSnapshot>())
    override val contexts = MutableStateFlow(emptyList<DiagnosticContextSnapshot>())
    override val telemetry = MutableStateFlow(emptyList<DiagnosticTelemetrySample>())
    override val nativeEvents = MutableStateFlow(emptyList<DiagnosticEvent>())
    override val liveSnapshots = MutableStateFlow(emptyList<DiagnosticNetworkSnapshot>())
    override val liveContexts = MutableStateFlow(emptyList<DiagnosticContextSnapshot>())
    override val liveTelemetry = MutableStateFlow(emptyList<DiagnosticTelemetrySample>())
    override val liveNativeEvents = MutableStateFlow(emptyList<DiagnosticEvent>())
    override val exports = MutableStateFlow(emptyList<DiagnosticExportRecord>())
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeStageExecutorVpnHaltTest {
    @Test
    fun `raw path stage ignores the expected vpn halt and waits for its session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val spec = stageSpec(ScanPathMode.RAW_PATH)
            val progressState = stageProgress(spec)
            timelineSource.sessions.value = listOf(stageSession(spec, status = "running"))
            val cpuReadings = ArrayDeque(listOf(1_000L, 1_125L))
            val cpuTracker = HomeCompositeStageCpuTracker { cpuReadings.removeFirst() }
            cpuTracker.start(SessionId)
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = unusedScanController(),
                    diagnosticsTimelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    stageTelemetryRecorder = HomeCompositeStageTelemetryRecorder(stores, serviceStateStore),
                    stageCpuTracker = cpuTracker,
                )

            val result =
                async {
                    executor.awaitStageSignal(
                        runId = RunId,
                        stageIndex = 0,
                        spec = spec,
                        stageSessionId = SessionId,
                        progressState = progressState,
                    )
                }
            runCurrent()

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            assertFalse(result.isCompleted)

            timelineSource.sessions.value = listOf(stageSession(spec, status = "completed"))
            val completed = result.await()
            assertEquals(SessionId, completed?.sessionId)
            assertEquals(125L, completed?.cpuMs)
            val sample = stores.telemetryState.value.single()
            assertEquals(RunId, sample.diagnosticsRunId)
            assertEquals(spec.key, sample.diagnosticsStageKey)
            assertEquals(SessionId, sample.sessionId)
            assertEquals("COMPLETED", sample.connectionState)
        }

    @Test
    fun `in path stage fails when vpn halts before its session completes`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val spec = stageSpec(ScanPathMode.IN_PATH)
            val progressState = stageProgress(spec)
            timelineSource.sessions.value = listOf(stageSession(spec, status = "running"))
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = unusedScanController(),
                    diagnosticsTimelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    stageTelemetryRecorder = HomeCompositeStageTelemetryRecorder(stores, serviceStateStore),
                    stageCpuTracker = HomeCompositeStageCpuTracker(),
                )

            val result =
                async {
                    executor.awaitStageSignal(
                        runId = RunId,
                        stageIndex = 0,
                        spec = spec,
                        stageSessionId = SessionId,
                        progressState = progressState,
                    )
                }
            runCurrent()

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertEquals(null, result.await())
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.FAILED,
                progressState.value
                    .getValue(RunId)
                    .stages
                    .single()
                    .status,
            )
            assertEquals(
                "FAILED",
                stores.telemetryState.value
                    .single()
                    .connectionState,
            )
        }

    private fun stageSpec(pathMode: ScanPathMode) =
        HomeCompositeStageSpec(
            key = "test-stage",
            label = "Test stage",
            profileId = "test-profile",
            pathMode = pathMode,
        )

    private fun stageProgress(spec: HomeCompositeStageSpec) =
        MutableStateFlow(
            mapOf(
                RunId to
                    DiagnosticsHomeCompositeProgress(
                        runId = RunId,
                        stages =
                            listOf(
                                DiagnosticsHomeCompositeStageSummary(
                                    stageKey = spec.key,
                                    stageLabel = spec.label,
                                    profileId = spec.profileId,
                                    pathMode = spec.pathMode,
                                    sessionId = SessionId,
                                    status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                                    headline = "Running",
                                    summary = "Running",
                                ),
                            ),
                    ),
            ),
        )

    private fun stageSession(
        spec: HomeCompositeStageSpec,
        status: String,
    ) = DiagnosticScanSession(
        id = SessionId,
        profileId = spec.profileId,
        pathMode = spec.pathMode.name,
        serviceMode = "VPN",
        status = status,
        summary = status,
        startedAt = 10L,
        finishedAt = if (status == "running") null else 20L,
    )

    private fun unusedScanController() = RecordingHomeCompositeScanController { _, _, _ -> error("unused") }

    private companion object {
        const val RunId = "run"
        const val SessionId = "session"
    }
}
