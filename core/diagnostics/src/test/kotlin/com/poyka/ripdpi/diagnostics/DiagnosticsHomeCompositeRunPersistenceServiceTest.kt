package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHomeCompositeRunPersistenceServiceTest {
    @Test
    fun `completed home run survives service recreation for archive export`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = PersistenceDiagnosticsTimelineSource()
            val scanController =
                PersistenceHomeCompositeScanController(
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
            val workflowService = recreatedRunWorkflowService()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val firstService =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanController,
                    workflowService = workflowService,
                    serviceStateStore = serviceStateStore,
                    scope = backgroundScope,
                )

            val started = firstService.startHomeAnalysis()
            advanceUntilIdle()
            val completed = firstService.finalizeHomeRun(started.runId)
            val recreatedService =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanController,
                    workflowService = workflowService,
                    serviceStateStore = serviceStateStore,
                    scope = backgroundScope,
                )

            val restored = recreatedService.getCompletedRun(started.runId)

            assertEquals(completed.runId, restored?.runId)
            assertEquals(completed.bundleSessionIds, restored?.bundleSessionIds)
            assertEquals(completed.stageSummaries.map { it.stageKey }, restored?.stageSummaries?.map { it.stageKey })
        }

    private fun recreatedRunWorkflowService(): DiagnosticsHomeWorkflowService =
        object : DiagnosticsHomeWorkflowService {
            override suspend fun currentFingerprintHash(): String = "fp-recreated"

            override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                DiagnosticsHomeAuditOutcome(
                    sessionId = sessionId,
                    fingerprintHash = "fp-recreated",
                    actionable = true,
                    headline = "Analysis complete and settings applied",
                    summary = "Reusable settings found.",
                    recommendationSummary = "TCP split",
                )

            override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                error("unused")
        }

    private fun diagnosticScanSession(
        sessionId: String,
        profileId: String,
        status: String,
    ): DiagnosticScanSession =
        DiagnosticScanSession(
            id = sessionId,
            profileId = profileId,
            pathMode = ScanPathMode.RAW_PATH.name,
            serviceMode = "VPN",
            status = status,
            summary = "Completed",
            startedAt = 10L,
            finishedAt = if (status == "completed" || status == "failed") 20L else null,
        )

    private fun createHomeCompositeRunService(
        stores: FakeDiagnosticsHistoryStores,
        timelineSource: PersistenceDiagnosticsTimelineSource,
        scanController: DiagnosticsScanController,
        workflowService: DiagnosticsHomeWorkflowService,
        serviceStateStore: FakeServiceStateStore,
        scope: CoroutineScope,
    ): DefaultDiagnosticsHomeCompositeRunService =
        DefaultDiagnosticsHomeCompositeRunService(
            detectionStageRunner = NoopHomeDetectionStageRunner,
            detectorCatalogSource = NoopHomeDetectorCatalogSource,
            analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
            networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
            diagnosticsProfileCatalog = stores,
            diagnosticsHomeWorkflowService = workflowService,
            persistencePorts =
                HomeCompositePersistencePorts(
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, diagnosticsTestJson()),
                    homeRunPersistence =
                        HomeDiagnosticsRunPersistence(
                            stores,
                            TestDiagnosticsHistoryClock(),
                            diagnosticsTestJson(),
                        ),
                    probeResultCache = PersistenceNoOpProbeResultCache(),
                ),
            networkHandoverMonitor = PersistenceNoOpNetworkHandoverMonitor(),
            serviceStateStore = serviceStateStore,
            vpnRouteEvidenceProvider = UnavailableVpnRouteEvidenceProvider,
            stageExecutor = HomeCompositeStageExecutor(scanController, timelineSource, serviceStateStore),
            json = diagnosticsTestJson(),
            scope = scope,
        )
}

private class PersistenceNoOpNetworkHandoverMonitor : NetworkHandoverMonitor {
    override val events = MutableSharedFlow<NetworkHandoverEvent>()
}

private class PersistenceHomeCompositeScanController(
    private val onStart: suspend (ScanPathMode, String?, String) -> Unit,
) : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
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

private class PersistenceNoOpProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

private class PersistenceDiagnosticsTimelineSource : DiagnosticsTimelineSource {
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
