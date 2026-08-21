package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositePathComparisonRetryContractTest {
    @Test
    fun `deterministic path comparison start failure is terminal without retry`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val timelineSource = defaultTimelineSource(stores, json, backgroundScope)
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
            var pathComparisonStarts = 0
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
                        if (selectedProfileId == "path-comparison") {
                            pathComparisonStarts += 1
                            return DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                                requestId = "path-comparison-rejected-$pathComparisonStarts",
                                profileName = "Path comparison",
                                pathMode = pathMode,
                                scanKind = ScanKind.CONNECTIVITY,
                                isFullAudit = false,
                            )
                        }
                        nextSessionId += 1
                        val sessionId = "scan-$nextSessionId"
                        val report =
                            if (selectedProfileId == "ru-dpi-full") {
                                pathComparisonEvidenceReport(sessionId, control, failed)
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
                                reportJson = json.encodeReport(report),
                            )
                        if (pathMode == ScanPathMode.RAW_PATH) {
                            stores.publishRawPathSettlementContext(sessionId, json)
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
                    persistencePorts =
                        HomeCompositePersistencePorts(
                            scanRecordStore = stores,
                            comparisonScanCoordinator = ComparisonScanCoordinator(stores, json),
                            homeRunPersistence =
                                HomeDiagnosticsRunPersistence(
                                    stores,
                                    TestDiagnosticsHistoryClock(),
                                    json,
                                ),
                            probeResultCache = NoopPathComparisonProbeResultCache,
                        ),
                    networkHandoverMonitor =
                        object : NetworkHandoverMonitor {
                            override val events = MutableSharedFlow<NetworkHandoverEvent>()
                        },
                    serviceStateStore = serviceStateStore,
                    vpnRouteEvidenceProvider = UnavailableVpnRouteEvidenceProvider,
                    stageExecutor = HomeCompositeStageExecutor(scanController, timelineSource, serviceStateStore),
                    json = json,
                    scope = backgroundScope,
                )

            val run = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(run.runId)

            assertEquals(
                1 to DiagnosticsHomeCompositeStageStatus.FAILED,
                pathComparisonStarts to
                    outcome.stageSummaries.single { it.stageKey == "path_comparison" }.status,
            )
        }
}

private object NoopPathComparisonProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

private fun defaultTimelineSource(
    stores: FakeDiagnosticsHistoryStores,
    json: Json,
    scope: CoroutineScope,
) = DefaultDiagnosticsTimelineSource(
    profileCatalog = stores,
    scanRecordStore = stores,
    artifactReadStore = stores,
    bypassUsageHistoryStore = stores,
    mapper = DiagnosticsBoundaryMapper(json),
    scope = scope,
    json = json,
)

private fun FakeDiagnosticsHistoryStores.publishRawPathSettlementContext(
    sessionId: String,
    json: Json,
) {
    contextsState.value =
        contextsState.value +
        DiagnosticContextEntity(
            id = "raw-path-settlement:$sessionId",
            sessionId = sessionId,
            contextKind = "post_runtime_restore",
            payloadJson =
                json.encodeToString(
                    RawPathExecutionResult.serializer(),
                    completedRawPathExecutionResult(),
                ),
            capturedAt = 30L,
        )
}

private fun pathComparisonEvidenceReport(
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
                domain =
                    DomainObservationFact(
                        host = failed.host,
                        transportFailure = TransportFailureKind.RESET,
                    ),
            ),
        ),
)

private fun Json.encodeReport(report: ScanReport): String =
    encodeToString(EngineScanReportWire.serializer(), report.toEngineScanReportWire())
