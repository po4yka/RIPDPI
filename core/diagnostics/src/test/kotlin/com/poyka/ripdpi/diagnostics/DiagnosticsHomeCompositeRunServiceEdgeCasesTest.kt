package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RawPathExecutionSettlement
import com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome
import com.poyka.ripdpi.data.RawPathRuntimeContext
import com.poyka.ripdpi.data.RawPathRuntimeStatus
import com.poyka.ripdpi.data.VpnRouteAppRoutingShape
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.VpnRouteLifecycleReceipt
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHomeCompositeRunServiceEdgeCasesTest {
    @Test
    fun `capture settlement retries before publishing a terminal home outcome`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val scanController = auditFailureScanController(stores, timelineSource)
            var settlementCalls = 0
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanController,
                    workflowService = auditFailureWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    scope = backgroundScope,
                )

            val started =
                service.startQuickAnalysis(
                    DiagnosticsHomeRunOptions(
                        packetCaptureRequested = true,
                        admitPacketCapture = {
                            DiagnosticsHomePacketCaptureDisposition(
                                requested = true,
                                outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                captureSetId = 41L,
                            )
                        },
                        settlePacketCapture = {
                            settlementCalls += 1
                            if (settlementCalls == 1) {
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                    captureSetId = 41L,
                                    failureCode = "stop_failed",
                                )
                            } else {
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                                    totalDrops = 3L,
                                )
                            }
                        },
                    ),
                )
            advanceUntilIdle()

            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(2, settlementCalls)
            assertEquals(
                DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                outcome.packetCaptureDisposition.outcome,
            )
            assertEquals(3L, outcome.packetCaptureDisposition.totalDrops)
        }

    @Test
    fun `capture settlement exhaustion publishes cleanup pending and recovers outside the home run`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val scanController = auditFailureScanController(stores, timelineSource)
            var settlementCalls = 0
            val allowRecovery = CompletableDeferred<Unit>()
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanController,
                    workflowService = auditFailureWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    scope = backgroundScope,
                )

            val started =
                service.startQuickAnalysis(
                    DiagnosticsHomeRunOptions(
                        packetCaptureRequested = true,
                        admitPacketCapture = {
                            DiagnosticsHomePacketCaptureDisposition(
                                requested = true,
                                outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                captureSetId = 42L,
                            )
                        },
                        settlePacketCapture = {
                            settlementCalls += 1
                            if (settlementCalls <= 2) {
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                    captureSetId = 42L,
                                    failureCode = "stop_failed",
                                )
                            } else {
                                allowRecovery.await()
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                                    totalDrops = 0L,
                                )
                            }
                        },
                    ),
                )

            advanceTimeBy(250)
            runCurrent()
            val outcome = service.finalizeHomeRun(started.runId)
            val progress = service.observeHomeRun(started.runId).first()

            assertEquals(
                DiagnosticsHomePacketCaptureOutcome.CLEANUP_PENDING,
                outcome.packetCaptureDisposition.outcome,
            )
            assertEquals("capture_cleanup_pending", outcome.packetCaptureDisposition.failureCode)
            assertTrue("expected recovery outside the terminal run", settlementCalls >= 3)
            assertEquals(DiagnosticsHomeCompositeRunStatus.COMPLETED, progress.status)
            assertEquals(
                DiagnosticsHomePacketCaptureOutcome.CLEANUP_PENDING,
                progress.outcome?.packetCaptureDisposition?.outcome,
            )

            allowRecovery.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `cancel uses bounded capture settlement and hands cleanup to recovery`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            var settlementCalls = 0
            val allowRecovery = CompletableDeferred<Unit>()
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = auditFailureScanController(stores, timelineSource),
                    workflowService = auditFailureWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    scope = backgroundScope,
                )
            val started =
                service.startQuickAnalysis(
                    DiagnosticsHomeRunOptions(
                        packetCaptureRequested = true,
                        admitPacketCapture = {
                            DiagnosticsHomePacketCaptureDisposition(
                                requested = true,
                                outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                captureSetId = 43L,
                            )
                        },
                        settlePacketCapture = {
                            settlementCalls += 1
                            if (settlementCalls <= 2) {
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                    captureSetId = 43L,
                                    failureCode = "stop_failed",
                                )
                            } else {
                                allowRecovery.await()
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                                    totalDrops = 0L,
                                )
                            }
                        },
                    ),
                )

            withTimeout(350) { service.cancelHomeRun(started.runId) }
            runCurrent()
            val progress = service.observeHomeRun(started.runId).first()

            assertEquals(DiagnosticsHomeCompositeRunStatus.CANCELLED, progress.status)
            assertTrue("expected recovery outside cancellation", settlementCalls >= 3)

            allowRecovery.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `capture admission completes before the first home stage starts`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val scanController =
                EdgeCaseHomeCompositeScanController { _, _, _ ->
                    error("stage started before admission")
                }
            val admissionStarted = CompletableDeferred<Unit>()
            val allowAdmission = CompletableDeferred<Unit>()
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanController,
                    workflowService = auditFailureWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                    scope = backgroundScope,
                )

            val start =
                async {
                    service.startHomeAnalysis(
                        DiagnosticsHomeRunOptions(
                            packetCaptureRequested = true,
                            admitPacketCapture = {
                                admissionStarted.complete(Unit)
                                allowAdmission.await()
                                DiagnosticsHomePacketCaptureDisposition(
                                    requested = true,
                                    outcome = DiagnosticsHomePacketCaptureOutcome.RECORDING,
                                    captureSetId = 7L,
                                )
                            },
                        ),
                    )
                }
            runCurrent()

            assertTrue(admissionStarted.isCompleted)
            assertFalse(scanController.startedRequests.isNotEmpty())
            allowAdmission.complete(Unit)
            start.await()
        }

    @Test
    fun `quick analysis captures generation bound passive vpn route evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController =
                        scanControllerWithAuditReport(
                            stores,
                            timelineSource,
                            dnsTamperingAuditReport().encodeScanReport(),
                        ),
                    workflowService = dnsIssueWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    vpnRouteEvidenceProvider = CapturedEdgeCaseVpnRouteEvidenceProvider,
                    scope = backgroundScope,
                )

            val started = service.startQuickAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)
            val passive = outcome.stageSummaries.single { it.profileId == "vpn-route-evidence" }

            assertEquals(DiagnosticsHomeCompositeStageStatus.COMPLETED, passive.status)
            assertEquals(HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE, passive.evidenceType)
            assertEquals(9L, passive.passiveVpnRouteEvidence?.vpnRouteEvidence?.lifecycleGeneration)
            assertEquals(HomeCompositeTargetReachability.UNVERIFIED, passive.targetReachability)
        }

    @Test
    fun `halted runtime preserves a completed raw path audit`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController =
                        scanControllerWithAuditReport(
                            stores,
                            timelineSource,
                            dnsTamperingAuditReport().encodeScanReport(),
                            completedRawPathResultForHaltedRuntime(),
                        ),
                    workflowService = dnsIssueWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(
                DiagnosticsHomeCompositeStageStatus.COMPLETED,
                outcome.stageSummaries.single { it.profileId == "automatic-audit" }.status,
            )
        }

    @Test
    fun `audit failure skips remaining stages`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val scanController = auditFailureScanController(stores, timelineSource)
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanController,
                    workflowService = auditFailureWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(1, scanController.startedRequests.size)
            assertEquals(0, outcome.completedStageCount)
            assertEquals(8, outcome.skippedStageCount)
            assertSkipped(outcome, "default")
            assertSkipped(outcome, "ru-dpi-full")
            assertSkipped(outcome, "vpn-route-evidence")
            assertSkipped(outcome, "ru-dpi-strategy")
        }

    @Test
    fun `dns issues detected appends note to summary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = EdgeCaseDiagnosticsTimelineSource()
            val auditReportJson = dnsTamperingAuditReport().encodeScanReport()
            val service =
                createHomeCompositeRunService(
                    stores = stores,
                    timelineSource = timelineSource,
                    scanController = scanControllerWithAuditReport(stores, timelineSource, auditReportJson),
                    workflowService = dnsIssueWorkflowService(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
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

    private fun auditFailureScanController(
        stores: FakeDiagnosticsHistoryStores,
        timelineSource: EdgeCaseDiagnosticsTimelineSource,
    ): EdgeCaseHomeCompositeScanController =
        EdgeCaseHomeCompositeScanController(
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

    private fun scanControllerWithAuditReport(
        stores: FakeDiagnosticsHistoryStores,
        timelineSource: EdgeCaseDiagnosticsTimelineSource,
        auditReportJson: String,
        rawPathExecutionResult: RawPathExecutionResult = completedRawPathExecutionResult(),
    ): EdgeCaseHomeCompositeScanController =
        EdgeCaseHomeCompositeScanController(
            onStart = { _, profileId, sessionId ->
                val session =
                    diagnosticsSession(
                        id = sessionId,
                        profileId = requireNotNull(profileId),
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "$profileId complete",
                        reportJson = auditReportJson.takeIf { profileId == "automatic-audit" },
                    )
                stores.sessionsState.value = stores.sessionsState.value + session
                timelineSource.sessions.value =
                    timelineSource.sessions.value + diagnosticScanSession(sessionId, profileId, "completed")
                publishRawPathSettlement(stores, timelineSource, sessionId, rawPathExecutionResult)
            },
        )

    private fun completedRawPathResultForHaltedRuntime(): RawPathExecutionResult =
        RawPathExecutionResult(
            settlement =
                RawPathExecutionSettlement(
                    rawWindowGeneration = 1L,
                    resumeIntentGeneration = 0L,
                    outcome = RawPathExecutionSettlementOutcome.RestoreNotRequired,
                    runtimeWasRunning = false,
                    resumeRequired = false,
                    postRuntimeContext =
                        RawPathRuntimeContext(
                            status = RawPathRuntimeStatus.Halted,
                            mode = Mode.Proxy,
                        ),
                ),
            executionOutcome = RawPathExecutionOutcome.Completed,
        )

    private fun auditFailureWorkflowService(): DiagnosticsHomeWorkflowService =
        object : DiagnosticsHomeWorkflowService {
            override suspend fun currentFingerprintHash(): String = "fp-3"

            override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
                error("should not be called when audit failed")

            override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
                error("unused")
        }

    private fun dnsIssueWorkflowService(): DiagnosticsHomeWorkflowService =
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

    private fun dnsTamperingAuditReport(): ScanReport =
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

    private fun ScanReport.encodeScanReport(): String =
        kotlinx.serialization.json.Json.encodeToString(
            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                .serializer(),
            toEngineScanReportWire(),
        )

    private fun assertSkipped(
        outcome: DiagnosticsHomeCompositeOutcome,
        profileId: String,
    ) {
        assertEquals(
            DiagnosticsHomeCompositeStageStatus.SKIPPED,
            outcome.stageSummaries.first { it.profileId == profileId }.status,
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

    private fun createHomeCompositeRunService(
        stores: FakeDiagnosticsHistoryStores,
        timelineSource: EdgeCaseDiagnosticsTimelineSource,
        scanController: DiagnosticsScanController,
        workflowService: DiagnosticsHomeWorkflowService,
        serviceStateStore: FakeServiceStateStore,
        vpnRouteEvidenceProvider: VpnRouteEvidenceProvider = UnavailableVpnRouteEvidenceProvider,
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
                    probeResultCache = EdgeCaseNoOpProbeResultCache(),
                ),
            networkHandoverMonitor = EdgeCaseNoOpNetworkHandoverMonitor(),
            serviceStateStore = serviceStateStore,
            vpnRouteEvidenceProvider = vpnRouteEvidenceProvider,
            stageExecutor = HomeCompositeStageExecutor(scanController, timelineSource, serviceStateStore),
            json = diagnosticsTestJson(),
            scope = scope,
        )
}

private fun publishRawPathSettlement(
    stores: FakeDiagnosticsHistoryStores,
    timelineSource: EdgeCaseDiagnosticsTimelineSource,
    sessionId: String,
    result: RawPathExecutionResult = completedRawPathExecutionResult(),
) {
    val contextId = "raw-path-settlement:$sessionId"
    stores.contextsState.value =
        stores.contextsState.value +
        DiagnosticContextEntity(
            id = contextId,
            sessionId = sessionId,
            contextKind = "post_runtime_restore",
            payloadJson = diagnosticsTestJson().encodeToString(RawPathExecutionResult.serializer(), result),
            capturedAt = 30L,
        )
    timelineSource.contexts.value =
        timelineSource.contexts.value +
        DiagnosticContextSnapshot(
            id = contextId,
            sessionId = sessionId,
            contextKind = "post_runtime_restore",
            rawPathExecutionResult = result,
            capturedAt = 30L,
        )
}

private class EdgeCaseNoOpNetworkHandoverMonitor : NetworkHandoverMonitor {
    override val events = MutableSharedFlow<NetworkHandoverEvent>()
}

private object CapturedEdgeCaseVpnRouteEvidenceProvider : VpnRouteEvidenceProvider {
    override val changes = MutableStateFlow(0L)

    override fun capture(): VpnRouteEvidence =
        VpnRouteEvidence(
            lifecycle =
                VpnRouteLifecycleReceipt(
                    generation = 9L,
                    state = VpnRouteLifecycleState.BridgeReady,
                    intendedDefaultRouteFamilies = listOf("ipv4"),
                    addressFamilies = listOf("ipv4"),
                    dnsServerFamilies = listOf("ipv4"),
                    appRoutingShape = VpnRouteAppRoutingShape.Disallow,
                    configuredAppCount = 1,
                    ownPackageExcluded = true,
                    ipv6Intent = false,
                    mtuBand = "standard",
                    metered = false,
                ),
            callbackState = VpnRouteCallbackState.Complete,
            ownerVerification = VpnRouteOwnerVerification.Verified,
            routeConsistency = VpnRouteConsistency.Consistent,
            forwardingOutcome = "cross_layer_return_observed",
            forwardingLifecycleGeneration = 9L,
        )
}

private class EdgeCaseHomeCompositeScanController(
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

private class EdgeCaseNoOpProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

private class EdgeCaseDiagnosticsTimelineSource : DiagnosticsTimelineSource {
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
