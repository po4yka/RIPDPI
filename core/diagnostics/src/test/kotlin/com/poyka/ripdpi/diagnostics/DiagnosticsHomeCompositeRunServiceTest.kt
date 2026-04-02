package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.services.NetworkHandoverEvent
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(
                listOf(
                    ScanPathMode.RAW_PATH to "automatic-audit",
                    ScanPathMode.RAW_PATH to "default",
                    ScanPathMode.RAW_PATH to "ru-dpi-full",
                ),
                scanController.startedRequests,
            )
            assertTrue(outcome.actionable)
            assertEquals("scan-1", outcome.recommendedSessionId)
            assertEquals(listOf("scan-1", "scan-2", "scan-3"), outcome.bundleSessionIds)
            assertEquals(3, outcome.completedStageCount)
            assertEquals(0, outcome.failedStageCount)
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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            assertEquals(3, scanController.startedRequests.size)
            assertFalse(outcome.actionable)
            assertEquals(2, outcome.completedStageCount)
            assertEquals(1, outcome.failedStageCount)
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.FAILED,
                outcome.stageSummaries.first { it.profileId == "default" }.status,
            )
            assertTrue(outcome.bundleSessionIds.contains("scan-3"))
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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            // Only audit stage ran; remaining 2 were skipped
            assertEquals(1, scanController.startedRequests.size)
            assertEquals(0, outcome.completedStageCount)
            assertEquals(2, outcome.skippedStageCount)
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.SKIPPED,
                outcome.stageSummaries.first { it.profileId == "default" }.status,
            )
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.SKIPPED,
                outcome.stageSummaries.first { it.profileId == "ru-dpi-full" }.status,
            )
        }

    @Test
    fun `network change during run appends warning to summary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()
            val handoverEvents = MutableSharedFlow<NetworkHandoverEvent>(extraBufferCapacity = 1)
            val monitor =
                object : NetworkHandoverMonitor {
                    override val events = handoverEvents
                }

            val scanController =
                RecordingHomeCompositeScanController(
                    onStart = { _, profileId, sessionId ->
                        // Emit a network handover event when the second stage (default) starts
                        if (profileId == "default") {
                            handoverEvents.tryEmit(
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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = monitor,
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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
                    json = diagnosticsTestJson(),
                    scope = backgroundScope,
                )

            val started = service.startHomeAnalysis()
            advanceUntilIdle()
            val outcome = service.finalizeHomeRun(started.runId)

            // All 3 stages should have eventually completed (default retried once)
            assertEquals(3, outcome.completedStageCount)
            assertEquals(0, outcome.failedStageCount)
            // "default" was attempted twice
            assertEquals(2, attemptCounts["default"])
        }

    @Test
    fun `cross-stage validation detects coverage gaps`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = MutableDiagnosticsTimelineSource()

            // Build a scan report for the audit stage with targetSelection listing "youtube.com"
            val auditReport =
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
                                    cohortId = "cohort-1",
                                    cohortLabel = "Cohort 1",
                                    domainHosts = listOf("youtube.com"),
                                ),
                        ),
                )

            // Build a scan report for dpi_full with a domain NOT in audit set that has a transport failure
            val dpiFullReport =
                ScanReport(
                    sessionId = "scan-3",
                    profileId = "ru-dpi-full",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "DPI full complete",
                    observations =
                        listOf(
                            ObservationFact(
                                kind = ObservationKind.DOMAIN,
                                target = "meduza.io",
                                domain =
                                    DomainObservationFact(
                                        host = "meduza.io",
                                        transportFailure = TransportFailureKind.RESET,
                                    ),
                            ),
                        ),
                )

            val auditReportJson =
                kotlinx.serialization.json.Json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                        .serializer(),
                    auditReport.toEngineScanReportWire(),
                )
            val dpiFullReportJson =
                kotlinx.serialization.json.Json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                        .serializer(),
                    dpiFullReport.toEngineScanReportWire(),
                )

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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
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
            val service =
                DefaultDiagnosticsHomeCompositeRunService(
                    diagnosticsScanController = scanController,
                    diagnosticsTimelineSource = timelineSource,
                    diagnosticsHomeWorkflowService = workflowService,
                    scanRecordStore = stores,
                    networkHandoverMonitor = NoOpNetworkHandoverMonitor(),
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
