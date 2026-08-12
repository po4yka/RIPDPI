package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositePathComparisonConsentTest {
    @Test
    fun `full analysis grants sensitive profile consent only to path comparison`() =
        runTest {
            val ordinarySpec = HomeCompositeStageSpecs.single { it.key == "automatic_audit" }
            val pathComparisonSpec = HomeCompositeStageSpecs.single { it.key == "path_comparison" }
            val controller = FakeDiagnosticsScanController()
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = EmptyDiagnosticsTimelineSource(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                )
            val progressState =
                MutableStateFlow(
                    mapOf(
                        RunId to
                            DiagnosticsHomeCompositeProgress(
                                runId = RunId,
                                stages =
                                    listOf(ordinarySpec, pathComparisonSpec).map { spec ->
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = spec.key,
                                            stageLabel = spec.label,
                                            profileId = spec.profileId,
                                            pathMode = spec.pathMode,
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "Pending",
                                            summary = "Pending",
                                        )
                                    },
                            ),
                    ),
                )

            executor.launchStageSession(
                runId = RunId,
                stageIndex = 0,
                spec = ordinarySpec,
                quickScan = false,
                progressState = progressState,
            )
            executor.launchStageSession(
                runId = RunId,
                stageIndex = 1,
                spec = pathComparisonSpec,
                quickScan = false,
                progressState = progressState,
            )

            assertEquals(
                listOf(
                    SensitiveStartRequest("automatic-audit", allowSensitiveProfileStart = false),
                    SensitiveStartRequest("path-comparison", allowSensitiveProfileStart = true),
                ),
                controller.requests,
            )
        }

    private companion object {
        const val RunId = "full-analysis-run"
    }
}

private data class SensitiveStartRequest(
    val profileId: String?,
    val allowSensitiveProfileStart: Boolean,
)

private class FakeDiagnosticsScanController : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
    val requests = mutableListOf<SensitiveStartRequest>()
    private var nextSessionId = 0

    override suspend fun startScan(
        pathMode: ScanPathMode,
        selectedProfileId: String?,
        skipActiveScanCheck: Boolean,
        allowSensitiveProfileStart: Boolean,
        scanDeadlineMs: Long?,
        maxCandidates: Int?,
        targetOverrides: DiagnosticsScanTargetOverrides?,
    ): DiagnosticsManualScanStartResult = error("composite stages must use the owned scan API")

    override suspend fun startScanOwnedBy(
        ownerId: String,
        pathMode: ScanPathMode,
        selectedProfileId: String?,
        skipActiveScanCheck: Boolean,
        allowSensitiveProfileStart: Boolean,
        scanDeadlineMs: Long?,
        maxCandidates: Int?,
        targetOverrides: DiagnosticsScanTargetOverrides?,
    ): DiagnosticsManualScanStartResult {
        requests += SensitiveStartRequest(selectedProfileId, allowSensitiveProfileStart)
        nextSessionId += 1
        return DiagnosticsManualScanStartResult.Started("scan-$nextSessionId")
    }

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution = error("unused")

    override suspend fun cancelActiveScan() = Unit

    override suspend fun setActiveProfile(profileId: String) = Unit
}

private class EmptyDiagnosticsTimelineSource : DiagnosticsTimelineSource {
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
