package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeStoppedRuntimePathComparisonTest {
    @Test
    fun `newer user stop after raw stages skips physical path comparison`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val timelineSource =
                DefaultDiagnosticsTimelineSource(
                    profileCatalog = stores,
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    bypassUsageHistoryStore = stores,
                    mapper = DiagnosticsBoundaryMapper(json),
                    scope = backgroundScope,
                    json = json,
                )
            var nextSessionId = 0
            var inPathStarts = 0
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
                    ): DiagnosticsManualScanStartResult {
                        if (pathMode == ScanPathMode.IN_PATH) inPathStarts += 1
                        nextSessionId += 1
                        val sessionId = "scan-$nextSessionId"
                        stores.sessionsState.value =
                            stores.sessionsState.value +
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(selectedProfileId),
                                pathMode = pathMode.name,
                                summary = "$selectedProfileId complete",
                            )
                        if (selectedProfileId == "ru-dpi-full") {
                            serviceStateStore.setStatus(AppStatus.Halted, Mode.Proxy)
                        }
                        return DiagnosticsManualScanStartResult.Started(sessionId)
                    }

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() = Unit

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    detectionStageRunner = NoopHomeDetectionStageRunner,
                    detectorCatalogSource = NoopHomeDetectorCatalogSource,
                    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
                    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
                    diagnosticsProfileCatalog = stores,
                    diagnosticsHomeWorkflowService =
                        object : DiagnosticsHomeWorkflowService {
                            override suspend fun currentFingerprintHash(): String = "fingerprint"

                            override suspend fun finalizeHomeAudit(sessionId: String) =
                                DiagnosticsHomeAuditOutcome(
                                    sessionId = sessionId,
                                    actionable = false,
                                    headline = "Audit complete",
                                    summary = "Audit complete",
                                )

                            override suspend fun summarizeVerification(
                                sessionId: String,
                            ): DiagnosticsHomeVerificationOutcome = error("unused")
                        },
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, json),
                    networkHandoverMonitor =
                        object : NetworkHandoverMonitor {
                            override val events = MutableSharedFlow<NetworkHandoverEvent>()
                        },
                    serviceStateStore = serviceStateStore,
                    probeResultCache = StoppedRuntimeProbeResultCache,
                    stageExecutor = HomeCompositeStageExecutor(scanController, timelineSource, serviceStateStore),
                    json = json,
                    scope = backgroundScope,
                )

            val run = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(run.runId)

            assertEquals(
                0 to DiagnosticsHomeCompositeStageStatus.SKIPPED,
                inPathStarts to outcome.stageSummaries.single { it.stageKey == "path_comparison" }.status,
            )
        }
}

private object StoppedRuntimeProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}
