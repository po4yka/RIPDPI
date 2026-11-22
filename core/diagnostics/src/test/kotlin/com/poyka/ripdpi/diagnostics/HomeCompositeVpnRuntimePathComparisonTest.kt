package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.VpnRouteAppRoutingShape
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.VpnRouteLifecycleReceipt
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeVpnRuntimePathComparisonTest {
    @Test
    fun `running vpn with owned proxy lease executes proxy vantage comparison`() =
        runTest {
            val fixture = vpnPathComparisonFixture(backgroundScope)

            val run = fixture.service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = fixture.service.finalizeHomeRun(run.runId)

            assertEquals(
                1 to DiagnosticsHomeCompositeStageStatus.COMPLETED,
                fixture.scanController.inPathStarts to
                    outcome.stageSummaries.single { it.stageKey == "path_comparison" }.status,
            )
            val passiveVpn = outcome.stageSummaries.single { it.stageKey == "vpn_route_evidence" }
            assertEquals(null, passiveVpn.sessionId)
            assertEquals(null, passiveVpn.pathMode)
            assertEquals(DiagnosticsHomeCompositeStageStatus.COMPLETED, passiveVpn.status)
            assertEquals(7L, passiveVpn.passiveVpnRouteEvidence?.vpnRouteEvidence?.lifecycleGeneration)
            assertEquals(HomeCompositeTargetReachability.UNVERIFIED, passiveVpn.targetReachability)
        }

    @Test
    fun `home compares a target with a complete RAW response and reports its VPN body stall`() =
        runTest {
            val fixture = vpnPathComparisonFixture(backgroundScope, targetWorksOnRaw = true)

            val run = fixture.service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = fixture.service.finalizeHomeRun(run.runId)

            assertEquals(
                DiagnosticsHomeCompositeStageStatus.COMPLETED,
                outcome.stageSummaries.single { it.stageKey == "path_comparison" }.status,
            )
            assertEquals(ConnectivityAssessmentCode.VPN_PATH_REGRESSION, outcome.connectivityAssessment?.assessmentCode)
            assertEquals(listOf("affected.example"), outcome.connectivityAssessment?.inPathEvidence?.affectedTargets)
        }

    @Test
    fun `home shows direct measurements when the active comparison was skipped`() =
        runTest {
            val fixture = vpnPathComparisonFixture(backgroundScope, controlTier = ControlTier.DOMESTIC)

            val run = fixture.service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = fixture.service.finalizeHomeRun(run.runId)
            val assessment = requireNotNull(outcome.connectivityAssessment)
            val comparison = requireNotNull(assessment.pathComparison)
            val affected = comparison.stages.single { it.target == "affected.example" && it.stage == "http" }

            assertEquals(0, fixture.scanController.inPathStarts)
            assertEquals("low", assessment.confidence)
            assertEquals(ConnectivityActivePathCoverage.NOT_RUN, comparison.activePathCoverage)
            assertEquals(ConnectivityStageMeasurement(ConnectivityStageOutcome.FAILED, 1), affected.raw)
            assertEquals(ConnectivityStageMeasurement(ConnectivityStageOutcome.UNMEASURED, 0), affected.inPath)
        }

    @Test
    fun `home reports HTTP recovery from its planned pair on an owned VPN route`() =
        runTest {
            val fixture = vpnPathComparisonFixture(backgroundScope, targetRecoversInPath = true)

            val run = fixture.service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = fixture.service.finalizeHomeRun(run.runId)
            val assessment = requireNotNull(outcome.connectivityAssessment)
            val comparison = requireNotNull(assessment.pathComparison)

            assertEquals(true, comparison.activePathIsVpn)
            assertEquals(listOf("affected.example"), comparison.httpRecoveredTargets)
            assertEquals("medium", assessment.confidence)
        }
}

private data class VpnPathComparisonFixture(
    val service: DefaultDiagnosticsHomeCompositeRunService,
    val scanController: VpnPathScanController,
)

private class VpnPathScanController(
    private val stores: FakeDiagnosticsHistoryStores,
    private val json: Json,
    private val control: DomainTarget,
    private val affected: DomainTarget,
    private val targetWorksOnRaw: Boolean,
    private val targetRecoversInPath: Boolean,
) : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
    var inPathStarts = 0
        private set
    private var nextSessionId = 0

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
        val profileId = requireNotNull(selectedProfileId)
        val report =
            when (profileId) {
                "ru-dpi-full" -> {
                    val rawStatus = if (targetWorksOnRaw) HttpProbeStatus.OK else HttpProbeStatus.UNREACHABLE
                    vpnPathComparisonEvidenceReport(
                        sessionId,
                        profileId,
                        pathMode,
                        listOf(control, affected),
                        rawStatus,
                    )
                }

                "path-comparison" -> {
                    val activeStatus = if (targetRecoversInPath) HttpProbeStatus.OK else HttpProbeStatus.UNREACHABLE
                    vpnPathComparisonEvidenceReport(
                        sessionId,
                        profileId,
                        pathMode,
                        requireNotNull(targetOverrides).domainTargets.orEmpty(),
                        activeStatus,
                    )
                }

                else -> {
                    ScanReport(sessionId, profileId, pathMode, 10L, 20L, "$profileId complete")
                }
            }
        stores.sessionsState.value =
            stores.sessionsState.value +
            diagnosticsSession(
                id = sessionId,
                profileId = profileId,
                pathMode = pathMode.name,
                summary = report.summary,
                reportJson = json.encodeVpnPathReport(report),
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

private fun vpnPathComparisonFixture(
    scope: CoroutineScope,
    targetWorksOnRaw: Boolean = false,
    targetRecoversInPath: Boolean = false,
    controlTier: ControlTier? = null,
): VpnPathComparisonFixture {
    val json = diagnosticsTestJson()
    val stores = FakeDiagnosticsHistoryStores()
    val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
    val control = DomainTarget(host = "control.example", isControl = true, controlTier = controlTier)
    val affected = DomainTarget(host = "affected.example")
    stores.profilesState.value = listOf(vpnPathProfile(json, control, affected))
    val scanController = VpnPathScanController(stores, json, control, affected, targetWorksOnRaw, targetRecoversInPath)
    val timelineSource = vpnPathTimelineSource(stores, json, scope)
    val service =
        createVpnPathCompositeService(
            stores = stores,
            json = json,
            scope = scope,
            serviceStateStore = serviceStateStore,
            scanController = scanController,
            timelineSource = timelineSource,
            vpnRouteEvidenceProvider = CurrentVpnRouteEvidenceProvider,
        )
    return VpnPathComparisonFixture(service, scanController)
}

private fun vpnPathProfile(
    json: Json,
    control: DomainTarget,
    failed: DomainTarget,
) = DiagnosticProfileEntity(
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
)

private fun vpnPathTimelineSource(
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

private fun createVpnPathCompositeService(
    stores: FakeDiagnosticsHistoryStores,
    json: Json,
    scope: CoroutineScope,
    serviceStateStore: FakeServiceStateStore,
    scanController: VpnPathScanController,
    timelineSource: DiagnosticsTimelineSource,
    vpnRouteEvidenceProvider: VpnRouteEvidenceProvider,
) = DefaultDiagnosticsHomeCompositeRunService(
    detectionStageRunner = NoopHomeDetectionStageRunner,
    detectorCatalogSource = NoopHomeDetectorCatalogSource,
    analysisAugmentationSource = NoopHomeAnalysisAugmentationSource,
    networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
    diagnosticsProfileCatalog = stores,
    diagnosticsHomeWorkflowService = vpnPathWorkflowService(),
    persistencePorts =
        HomeCompositePersistencePorts(
            scanRecordStore = stores,
            comparisonScanCoordinator = ComparisonScanCoordinator(),
            homeRunPersistence = HomeDiagnosticsRunPersistence(stores, TestDiagnosticsHistoryClock(), json),
            probeResultCache = VpnRuntimeProbeResultCache,
        ),
    networkScopeFactory = stableHomeNetworkScopeFactory(),
    networkHandoverMonitor =
        object : NetworkHandoverMonitor {
            override val events = MutableSharedFlow<NetworkHandoverEvent>()
        },
    serviceStateStore = serviceStateStore,
    vpnRouteEvidenceProvider = vpnRouteEvidenceProvider,
    stageExecutor =
        HomeCompositeStageExecutor(
            scanController,
            timelineSource,
            serviceStateStore,
            vpnInPathRuntimeCoordinator(),
            homeRunLease = DiagnosticsHomeRunLease(),
        ),
    json = json,
    scope = scope,
)

private object CurrentVpnRouteEvidenceProvider : VpnRouteEvidenceProvider {
    override val changes: StateFlow<Long> = MutableStateFlow(0L)

    override fun capture(): VpnRouteEvidence =
        VpnRouteEvidence(
            lifecycle =
                VpnRouteLifecycleReceipt(
                    generation = 7L,
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
            forwardingLifecycleGeneration = 7L,
        )
}

private fun vpnPathWorkflowService(): DiagnosticsHomeWorkflowService =
    object : DiagnosticsHomeWorkflowService {
        override suspend fun currentFingerprintHash(): String = "fingerprint"

        override suspend fun finalizeHomeAudit(sessionId: String) =
            DiagnosticsHomeAuditOutcome(
                sessionId = sessionId,
                actionable = false,
                headline = "Audit complete",
                summary = "Audit complete",
            )

        override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
            error("unused")
    }

private fun vpnInPathRuntimeCoordinator() =
    FakeDiagnosticsRuntimeCoordinator(
        DiagnosticsInPathRouteLease(
            runtimeId = "vpn-runtime",
            routeGeneration = 1,
            issuedRevision = 1L,
            host = "127.0.0.1",
            port = 19080,
            credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
        ),
    )

private object VpnRuntimeProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

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

private fun vpnPathComparisonEvidenceReport(
    sessionId: String,
    profileId: String,
    pathMode: ScanPathMode,
    targets: List<DomainTarget>,
    affectedStatus: HttpProbeStatus,
): ScanReport {
    val observations =
        targets.map { target ->
            ObservationFact(
                kind = ObservationKind.DOMAIN,
                target = target.host,
                domain =
                    DomainObservationFact(
                        host = target.host,
                        httpStatus = if (target.isControl) HttpProbeStatus.OK else affectedStatus,
                        tls13Status = TlsProbeStatus.OK,
                        tls12Status = TlsProbeStatus.OK,
                        isControl = target.isControl,
                    ),
            )
        }
    return ScanReport(
        sessionId = sessionId,
        profileId = profileId,
        pathMode = pathMode,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "Domain reachability",
        results =
            observations.map { observation ->
                val httpOk = observation.domain?.httpStatus == HttpProbeStatus.OK
                ProbeResult(
                    "domain_reachability",
                    observation.target,
                    "tls_ok",
                    listOf(
                        ProbeDetail("httpStatus", if (httpOk) "http_ok" else "http_unreachable"),
                        ProbeDetail("httpResponse", if (httpOk) "200 OK server=fixture" else "operation timed out"),
                    ),
                )
            },
        observations = observations,
    )
}

private fun Json.encodeVpnPathReport(report: ScanReport): String =
    encodeToString(EngineScanReportWire.serializer(), report.toEngineScanReportWire())
