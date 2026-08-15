package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeVpnRuntimePathComparisonTest {
    @Test
    fun `running vpn runtime skips physical in path comparison`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
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
            val control = DomainTarget(host = "control.example", isControl = true)
            val failed = DomainTarget(host = "failed.example")
            stores.profilesState.value =
                listOf(
                    DiagnosticProfileEntity(
                        id = "ru-dpi-full",
                        name = "DPI full",
                        source = "bundled",
                        version = 1,
                        requestJson =
                            diagnosticsProfileRequestJson(
                                json = json,
                                profileId = "ru-dpi-full",
                                displayName = "DPI full",
                                targets = DiagnosticsProfileTargets(domainTargets = listOf(control, failed)),
                            ),
                        updatedAt = 1L,
                    ),
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
                        val report =
                            if (selectedProfileId == "ru-dpi-full") {
                                vpnPathComparisonEvidenceReport(sessionId, control, failed)
                            } else {
                                ScanReport(
                                    sessionId = sessionId,
                                    profileId = requireNotNull(selectedProfileId),
                                    pathMode = pathMode,
                                    startedAt = 10L,
                                    finishedAt = 20L,
                                    summary = "$selectedProfileId complete",
                                )
                            }
                        stores.sessionsState.value =
                            stores.sessionsState.value +
                            diagnosticsSession(
                                id = sessionId,
                                profileId = requireNotNull(selectedProfileId),
                                pathMode = pathMode.name,
                                summary = report.summary,
                                reportJson = json.encodeVpnPathReport(report),
                            )
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
                    probeResultCache = VpnRuntimeProbeResultCache,
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

private object VpnRuntimeProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

private fun vpnPathComparisonEvidenceReport(
    sessionId: String,
    control: DomainTarget,
    failed: DomainTarget,
) = ScanReport(
    sessionId = sessionId,
    profileId = "ru-dpi-full",
    pathMode = ScanPathMode.RAW_PATH,
    startedAt = 10L,
    finishedAt = 20L,
    summary = "Control succeeds while target fails",
    observations =
        listOf(
            ObservationFact(
                kind = ObservationKind.DOMAIN,
                target = control.host,
                domain = DomainObservationFact(host = control.host, tls13Status = TlsProbeStatus.OK, isControl = true),
            ),
            ObservationFact(
                kind = ObservationKind.DOMAIN,
                target = failed.host,
                domain = DomainObservationFact(host = failed.host, transportFailure = TransportFailureKind.RESET),
            ),
        ),
)

private fun Json.encodeVpnPathReport(report: ScanReport): String =
    encodeToString(EngineScanReportWire.serializer(), report.toEngineScanReportWire())
