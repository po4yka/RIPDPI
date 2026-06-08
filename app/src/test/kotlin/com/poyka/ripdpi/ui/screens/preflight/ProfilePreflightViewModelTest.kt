package com.poyka.ripdpi.ui.screens.preflight

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsScanTargetOverrides
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.HiddenProbeConflictAction
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePreflightViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `runPreflight starts bounded in path scan and shares pass verdict`() =
        runTest {
            val timeline = FakeDiagnosticsTimelineSource()
            val controller =
                FakeDiagnosticsScanController(
                    timeline = timeline,
                    session =
                        session(
                            status = "completed",
                            summary = "Profile reached the target",
                        ),
                )
            val viewModel = testViewModel(controller = controller, timeline = timeline)

            viewModel.runPreflight()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(ProfilePreflightRunState.Passed, state.runState)
            assertEquals("quick_v1", state.activeProfileId)
            assertEquals("session-1", state.sessionId)
            assertTrue(state.shareBody.orEmpty().contains("verdict=pass"))
            assertTrue(state.shareBody.orEmpty().contains("redacted diagnostics body"))
            assertEquals(ScanPathMode.IN_PATH, controller.pathMode)
            assertEquals("quick_v1", controller.selectedProfileId)
            assertEquals(75_000L, controller.scanDeadlineMs)
            assertEquals(1, controller.maxCandidates)
        }

    @Test
    fun `runPreflight shares fail verdict when completed scan has no passing probe outcome`() =
        runTest {
            val timeline = FakeDiagnosticsTimelineSource()
            val controller =
                FakeDiagnosticsScanController(
                    timeline = timeline,
                    session =
                        session(
                            status = "completed",
                            summary = "Profile could not reach the target",
                            report =
                                DiagnosticsSessionProjection(
                                    results =
                                        listOf(
                                            ProbeResult(
                                                probeType = "connectivity",
                                                target = "example.com",
                                                outcome = "tls_handshake_failed",
                                            ),
                                        ),
                                ),
                        ),
                )
            val viewModel = testViewModel(controller = controller, timeline = timeline)

            viewModel.runPreflight()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(ProfilePreflightRunState.Failed, state.runState)
            assertTrue(state.shareBody.orEmpty().contains("verdict=fail"))
            assertTrue(state.shareBody.orEmpty().contains("redacted diagnostics body"))
        }

    @Test
    fun `runPreflight reports start errors`() =
        runTest {
            val timeline = FakeDiagnosticsTimelineSource()
            val controller =
                FakeDiagnosticsScanController(
                    timeline = timeline,
                    startError = IllegalStateException("busy"),
                )
            val viewModel = testViewModel(controller = controller, timeline = timeline)

            viewModel.runPreflight()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(ProfilePreflightRunState.Error, state.runState)
            assertEquals("busy", state.errorMessage)
        }

    private fun testViewModel(
        controller: FakeDiagnosticsScanController,
        timeline: FakeDiagnosticsTimelineSource,
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        shareService: DiagnosticsShareService = FakeDiagnosticsShareService(),
    ): ProfilePreflightViewModel =
        ProfilePreflightViewModel(
            appSettingsRepository = repository,
            runner =
                ProfilePreflightRunner(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = timeline,
                    diagnosticsShareService = shareService,
                ),
        )
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val state =
        MutableStateFlow(
            AppSettings
                .getDefaultInstance()
                .toBuilder()
                .setDiagnosticsActiveProfileId("quick_v1")
                .build(),
        )

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state
                .value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class FakeDiagnosticsScanController(
    private val timeline: FakeDiagnosticsTimelineSource,
    private val session: DiagnosticScanSession = session(status = "completed"),
    private val startError: Throwable? = null,
) : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
    var pathMode: ScanPathMode? = null
    var selectedProfileId: String? = null
    var scanDeadlineMs: Long? = null
    var maxCandidates: Int? = null

    override suspend fun startScan(
        pathMode: ScanPathMode,
        selectedProfileId: String?,
        skipActiveScanCheck: Boolean,
        allowSensitiveProfileStart: Boolean,
        scanDeadlineMs: Long?,
        maxCandidates: Int?,
        targetOverrides: DiagnosticsScanTargetOverrides?,
    ): DiagnosticsManualScanStartResult {
        startError?.let { throw it }
        this.pathMode = pathMode
        this.selectedProfileId = selectedProfileId
        this.scanDeadlineMs = scanDeadlineMs
        this.maxCandidates = maxCandidates
        timeline.emit(session.copy(profileId = selectedProfileId.orEmpty(), pathMode = pathMode.name))
        return DiagnosticsManualScanStartResult.Started(session.id)
    }

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution = error("Not used")

    override suspend fun cancelActiveScan() = Unit

    override suspend fun setActiveProfile(profileId: String) = Unit
}

private class FakeDiagnosticsTimelineSource : DiagnosticsTimelineSource {
    private val mutableSessions = MutableStateFlow(emptyList<DiagnosticScanSession>())

    override val activeScanProgress: StateFlow<ScanProgress?> = MutableStateFlow(null)
    override val activeConnectionSession: StateFlow<DiagnosticConnectionSession?> = MutableStateFlow(null)
    override val profiles: Flow<List<DiagnosticProfile>> = flowOf(emptyList())
    override val sessions: Flow<List<DiagnosticScanSession>> = mutableSessions
    override val approachStats: Flow<List<BypassApproachSummary>> = flowOf(emptyList())
    override val snapshots: Flow<List<DiagnosticNetworkSnapshot>> = flowOf(emptyList())
    override val contexts: Flow<List<DiagnosticContextSnapshot>> = flowOf(emptyList())
    override val telemetry: Flow<List<DiagnosticTelemetrySample>> = flowOf(emptyList())
    override val nativeEvents: Flow<List<DiagnosticEvent>> = flowOf(emptyList())
    override val liveSnapshots: Flow<List<DiagnosticNetworkSnapshot>> = flowOf(emptyList())
    override val liveContexts: Flow<List<DiagnosticContextSnapshot>> = flowOf(emptyList())
    override val liveTelemetry: Flow<List<DiagnosticTelemetrySample>> = flowOf(emptyList())
    override val liveNativeEvents: Flow<List<DiagnosticEvent>> = flowOf(emptyList())
    override val exports: Flow<List<DiagnosticExportRecord>> = flowOf(emptyList())

    fun emit(session: DiagnosticScanSession) {
        mutableSessions.value = listOf(session)
    }
}

private class FakeDiagnosticsShareService : DiagnosticsShareService {
    override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
        ShareSummary(
            title = "RIPDPI diagnostics $sessionId",
            body = "redacted diagnostics body",
        )

    override suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive = error("Not used")
}

private fun session(
    status: String,
    summary: String = "Preflight complete",
    report: DiagnosticsSessionProjection? = null,
): DiagnosticScanSession =
    DiagnosticScanSession(
        id = "session-1",
        profileId = "quick_v1",
        pathMode = ScanPathMode.IN_PATH.name,
        serviceMode = null,
        status = status,
        summary = summary,
        report = report,
        startedAt = 1L,
        finishedAt = 2L,
    )
