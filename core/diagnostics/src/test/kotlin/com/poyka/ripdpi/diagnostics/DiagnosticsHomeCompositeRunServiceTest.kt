package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
