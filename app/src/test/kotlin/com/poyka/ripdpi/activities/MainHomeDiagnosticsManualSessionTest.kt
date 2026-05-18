package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainHomeDiagnosticsManualSessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `latest user initiated diagnostic session updates home runtime state`() =
        runTest {
            val diagnosticsTimelineSource = StubDiagnosticsTimelineSource()
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                MainHomeDiagnosticsActions(
                    mutations =
                        MainMutationRunner(
                            scope = backgroundScope,
                            effects = MutableSharedFlow(replay = 16, extraBufferCapacity = 16),
                            currentUiState = { MainUiState() },
                        ),
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = StubDiagnosticsScanController(),
                    diagnosticsShareService = StubDiagnosticsShareService(),
                    diagnosticsHomeWorkflowService = StubDiagnosticsHomeWorkflowService(),
                    diagnosticsHomeCompositeRunService = StubDiagnosticsHomeCompositeRunService(),
                    serviceStateStore = FakeServiceStateStore(),
                    latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
                    runtimeState = MutableStateFlow(ConnectionRuntimeState()),
                    permissionState =
                        MutableStateFlow(
                            PermissionRuntimeState(
                                snapshot =
                                    PermissionSnapshot(
                                        vpnConsent = PermissionStatus.Granted,
                                        notifications = PermissionStatus.Granted,
                                        batteryOptimization = PermissionStatus.Granted,
                                    ),
                            ),
                        ),
                    homeDiagnosticsState = homeDiagnosticsState,
                    stringResolver = FakeStringResolver(),
                    requestVpnStart = {},
                )

            actions.initialize()
            runCurrent()
            diagnosticsTimelineSource.sessions.value =
                listOf(
                    manualSession(id = "older", summary = "older", finishedAt = 20L),
                    manualSession(id = "latest", summary = "18 completed · 18 healthy", finishedAt = 30L),
                    manualSession(
                        id = "background",
                        summary = "background",
                        finishedAt = 40L,
                        launchOrigin = DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND,
                    ),
                    manualSession(id = "running", summary = "running", finishedAt = null),
                )
            runCurrent()

            val latest = homeDiagnosticsState.value.latestManualDiagnosticSession
            assertEquals("latest", latest?.id)
            assertEquals("18 completed · 18 healthy", latest?.summary)
        }

    private fun manualSession(
        id: String,
        summary: String,
        finishedAt: Long?,
        launchOrigin: DiagnosticsScanLaunchOrigin = DiagnosticsScanLaunchOrigin.USER_INITIATED,
    ): DiagnosticScanSession =
        DiagnosticScanSession(
            id = id,
            profileId = "default",
            pathMode = ScanPathMode.RAW_PATH.name,
            serviceMode = null,
            status = if (finishedAt == null) "running" else "completed",
            summary = summary,
            startedAt = 10L,
            finishedAt = finishedAt,
            launchOrigin = launchOrigin,
        )
}
