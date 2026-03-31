package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectionReason
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainHomeDiagnosticsActionsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `verified vpn flow starts in-path verification scan`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val diagnosticsTimelineSource = StubDiagnosticsTimelineSource()
            val diagnosticsScanController =
                StubDiagnosticsScanController().apply {
                    startResults += DiagnosticsManualScanStartResult.Started("verify-session")
                }
            val diagnosticsHomeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                    verificationOutcomes["verify-session"] =
                        DiagnosticsHomeVerificationOutcome(
                            sessionId = "verify-session",
                            success = true,
                            headline = "VPN access confirmed",
                            summary = "Traffic is reaching the network.",
                            detail = "HTTP and HTTPS probes passed.",
                        )
                }
            val serviceStateStore = FakeServiceStateStore()
            val runtimeState = MutableStateFlow(ConnectionRuntimeState())
            val permissionState =
                MutableStateFlow(
                    PermissionRuntimeState(
                        snapshot =
                            PermissionSnapshot(
                                vpnConsent = PermissionStatus.Granted,
                                notifications = PermissionStatus.Granted,
                                batteryOptimization = PermissionStatus.Granted,
                            ),
                    ),
                )
            val homeDiagnosticsState =
                MutableStateFlow(
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome =
                            DiagnosticsHomeCompositeOutcome(
                                runId = "home-run",
                                fingerprintHash = "fp-1",
                                actionable = true,
                                headline = "Applied",
                                summary = "Ready to verify",
                                recommendedSessionId = "audit-session",
                                stageSummaries =
                                    listOf(
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = "automatic_audit",
                                            stageLabel = "Automatic audit",
                                            profileId = "automatic-audit",
                                            pathMode = ScanPathMode.RAW_PATH,
                                            sessionId = "audit-session",
                                            status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                            headline = "Applied",
                                            summary = "Ready to verify",
                                            recommendationContributor = true,
                                        ),
                                    ),
                                completedStageCount = 1,
                                failedStageCount = 0,
                                skippedStageCount = 0,
                                bundleSessionIds = listOf("audit-session"),
                            ),
                        currentFingerprintHash = "fp-1",
                        waitingForVerifiedVpnStart = true,
                    ),
                )
            val diagnosticsHomeCompositeRunService = StubDiagnosticsHomeCompositeRunService()
            val actions =
                MainHomeDiagnosticsActions(
                    mutations =
                        MainMutationRunner(
                            scope = backgroundScope,
                            effects = effects,
                            currentUiState = { MainUiState() },
                        ),
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = diagnosticsScanController,
                    diagnosticsShareService = StubDiagnosticsShareService(),
                    diagnosticsHomeWorkflowService = diagnosticsHomeWorkflowService,
                    diagnosticsHomeCompositeRunService = diagnosticsHomeCompositeRunService,
                    serviceStateStore = serviceStateStore,
                    runtimeState = runtimeState,
                    permissionState = permissionState,
                    homeDiagnosticsState = homeDiagnosticsState,
                    stringResolver = FakeStringResolver(),
                    requestVpnStart = {},
                )

            actions.initialize()
            advanceUntilIdle()

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runtimeState.value = ConnectionRuntimeState(connectionState = ConnectionState.Connected)
            runCurrent()

            assertTrue(
                diagnosticsScanController.startedRequests.any { (pathMode, profileId) ->
                    pathMode == ScanPathMode.IN_PATH && profileId == "default"
                },
            )
            advanceUntilIdle()
            assertEquals("verify-session", homeDiagnosticsState.value.activeVerificationSessionId)
        }

    @Test
    fun `run full analysis clears stale state and starts composite run`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val homeWorkflowService = StubDiagnosticsHomeWorkflowService().apply { currentFingerprint = "fp-1" }
            val homeDiagnosticsState =
                MutableStateFlow(
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome = compositeOutcome(runId = "previous-run"),
                        analysisSheetVisible = true,
                        activeVerificationSessionId = "verify-session",
                        waitingForVerifiedVpnStart = true,
                        verificationProgress = "Verifying",
                        verificationSheet =
                            DiagnosticsHomeVerificationOutcome(
                                sessionId = "verify-session",
                                success = true,
                                headline = "VPN access confirmed",
                                summary = "Traffic is reaching the network.",
                            ),
                    ),
                )
            val actions =
                createActions(
                    scope = backgroundScope,
                    effects = effects,
                    diagnosticsHomeWorkflowService = homeWorkflowService,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            runCurrent()

            assertEquals(listOf("home-run"), compositeRunService.startedRunIds)
            assertEquals("home-run", homeDiagnosticsState.value.activeRunId)
            assertFalse(homeDiagnosticsState.value.analysisSheetVisible)
            assertNull(homeDiagnosticsState.value.activeVerificationSessionId)
            assertFalse(homeDiagnosticsState.value.waitingForVerifiedVpnStart)
            assertNull(homeDiagnosticsState.value.verificationSheet)
            assertTrue(effects.tryReceive().isFailure)
        }

    @Test
    fun `run full analysis surfaces start failures`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val compositeRunService =
                StubDiagnosticsHomeCompositeRunService().apply {
                    startFailure =
                        DiagnosticsScanStartRejectedException(
                            DiagnosticsScanStartRejectionReason.ScanAlreadyActive,
                        )
                }
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = this,
                    effects = effects,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            advanceUntilIdle()

            assertNull(homeDiagnosticsState.value.activeRunId)
            assertNull(homeDiagnosticsState.value.latestCompositeOutcome)
        }

    @Test
    fun `start verified vpn blocks stale composite results`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val homeWorkflowService = StubDiagnosticsHomeWorkflowService().apply { currentFingerprint = "fp-2" }
            val homeDiagnosticsState =
                MutableStateFlow(
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome = compositeOutcome(fingerprintHash = "fp-1"),
                        currentFingerprintHash = "fp-1",
                    ),
                )
            var vpnStartRequests = 0
            val actions =
                createActions(
                    scope = this,
                    effects = effects,
                    diagnosticsHomeWorkflowService = homeWorkflowService,
                    homeDiagnosticsState = homeDiagnosticsState,
                    requestVpnStart = { vpnStartRequests += 1 },
                )

            actions.startVerifiedVpn()
            advanceUntilIdle()

            assertEquals(0, vpnStartRequests)
            assertFalse(homeDiagnosticsState.value.waitingForVerifiedVpnStart)
            assertNull(homeDiagnosticsState.value.verificationSheet)
        }

    @Test
    fun `verified vpn flow surfaces hidden probe conflicts`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val diagnosticsTimelineSource = StubDiagnosticsTimelineSource()
            val diagnosticsScanController =
                StubDiagnosticsScanController().apply {
                    startResults +=
                        DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                            requestId = "probe-conflict",
                            profileName = "Default diagnostics",
                            pathMode = ScanPathMode.IN_PATH,
                            scanKind = ScanKind.CONNECTIVITY,
                            isFullAudit = false,
                        )
                }
            val serviceStateStore = FakeServiceStateStore()
            val runtimeState = MutableStateFlow(ConnectionRuntimeState())
            val permissionState =
                MutableStateFlow(
                    PermissionRuntimeState(
                        snapshot =
                            PermissionSnapshot(
                                vpnConsent = PermissionStatus.Granted,
                                notifications = PermissionStatus.Granted,
                                batteryOptimization = PermissionStatus.Granted,
                            ),
                    ),
                )
            val homeDiagnosticsState =
                MutableStateFlow(
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome = compositeOutcome(),
                        currentFingerprintHash = "fp-1",
                        waitingForVerifiedVpnStart = true,
                    ),
                )
            val actions =
                createActions(
                    scope = backgroundScope,
                    effects = effects,
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = diagnosticsScanController,
                    serviceStateStore = serviceStateStore,
                    runtimeState = runtimeState,
                    permissionState = permissionState,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.initialize()
            advanceUntilIdle()

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runtimeState.value = ConnectionRuntimeState(connectionState = ConnectionState.Connected)
            runCurrent()
            advanceUntilIdle()

            assertNull(homeDiagnosticsState.value.activeVerificationSessionId)
            assertFalse(homeDiagnosticsState.value.waitingForVerifiedVpnStart)
            assertEquals("VPN verification is busy", homeDiagnosticsState.value.verificationSheet?.headline)
            assertTrue(effects.tryReceive().isFailure)
        }

    @Test
    fun `buildHomeDiagnosticsUiState disables actions for busy and blocked states`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings =
                    com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setEnableCmdSettings(true)
                        .build(),
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                permissionSummary =
                    PermissionSummaryUiState(
                        snapshot =
                            PermissionSnapshot(
                                vpnConsent = PermissionStatus.Granted,
                                notifications = PermissionStatus.Granted,
                                batteryOptimization = PermissionStatus.Granted,
                            ),
                    ),
                runtime =
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome = compositeOutcome(fingerprintHash = "fp-1"),
                        currentFingerprintHash = "fp-2",
                        externalScanActive = true,
                        externalScanMessage = "Other scan running",
                    ),
                stringResolver = FakeStringResolver(),
            )

        assertFalse(uiState.analysisAction.enabled)
        assertFalse(uiState.verifiedVpnAction.enabled)
        assertEquals("Other scan running", uiState.verifiedVpnAction.supportingText)
    }

    @Test
    fun `share failure keeps analysis sheet open and emits error`() =
        runTest {
            val effects = Channel<MainEffect>(Channel.BUFFERED)
            val shareService =
                StubDiagnosticsShareService().apply {
                    archiveFailure = IllegalStateException("archive creation failed")
                }
            val homeDiagnosticsState =
                MutableStateFlow(
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome = compositeOutcome(),
                        analysisSheetVisible = true,
                    ),
                )
            val actions =
                createActions(
                    scope = backgroundScope,
                    effects = effects,
                    diagnosticsShareService = shareService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.shareLatestHomeAnalysis()
            runCurrent()
            advanceUntilIdle()

            assertTrue(
                "analysisSheetVisible should remain true after share failure",
                homeDiagnosticsState.value.analysisSheetVisible,
            )
            assertFalse(
                "shareBusy should be false after share failure",
                homeDiagnosticsState.value.shareBusy,
            )
            val effect = effects.tryReceive().getOrNull()
            assertTrue(
                "expected ShowError effect but got $effect",
                effect is MainEffect.ShowError,
            )
        }

    private fun createActions(
        scope: CoroutineScope,
        effects: Channel<MainEffect> = Channel(Channel.BUFFERED),
        diagnosticsTimelineSource: StubDiagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
        diagnosticsScanController: StubDiagnosticsScanController = StubDiagnosticsScanController(),
        diagnosticsShareService: StubDiagnosticsShareService = StubDiagnosticsShareService(),
        diagnosticsHomeWorkflowService: StubDiagnosticsHomeWorkflowService = StubDiagnosticsHomeWorkflowService(),
        diagnosticsHomeCompositeRunService: StubDiagnosticsHomeCompositeRunService =
            StubDiagnosticsHomeCompositeRunService(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        runtimeState: MutableStateFlow<ConnectionRuntimeState> = MutableStateFlow(ConnectionRuntimeState()),
        permissionState: MutableStateFlow<PermissionRuntimeState> =
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
        homeDiagnosticsState: MutableStateFlow<HomeDiagnosticsRuntimeState> =
            MutableStateFlow(HomeDiagnosticsRuntimeState()),
        requestVpnStart: () -> Unit = {},
    ): MainHomeDiagnosticsActions =
        MainHomeDiagnosticsActions(
            mutations =
                MainMutationRunner(
                    scope = scope,
                    effects = effects,
                    currentUiState = { MainUiState() },
                ),
            diagnosticsTimelineSource = diagnosticsTimelineSource,
            diagnosticsScanController = diagnosticsScanController,
            diagnosticsShareService = diagnosticsShareService,
            diagnosticsHomeWorkflowService = diagnosticsHomeWorkflowService,
            diagnosticsHomeCompositeRunService = diagnosticsHomeCompositeRunService,
            serviceStateStore = serviceStateStore,
            runtimeState = runtimeState,
            permissionState = permissionState,
            homeDiagnosticsState = homeDiagnosticsState,
            stringResolver = FakeStringResolver(),
            requestVpnStart = requestVpnStart,
        )

    private fun compositeOutcome(
        runId: String = "home-run",
        fingerprintHash: String = "fp-1",
        actionable: Boolean = true,
    ): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = runId,
            fingerprintHash = fingerprintHash,
            actionable = actionable,
            headline = "Analysis complete and settings applied",
            summary = "Ready to verify",
            recommendedSessionId = "audit-session",
            stageSummaries =
                listOf(
                    stage(
                        key = "automatic_audit",
                        label = "Automatic audit",
                        profileId = "automatic-audit",
                        sessionId = "audit-session",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        recommendationContributor = actionable,
                    ),
                    stage(
                        key = "default_connectivity",
                        label = "Default diagnostics",
                        profileId = "default",
                        sessionId = "default-session",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                    ),
                ),
            completedStageCount = 2,
            failedStageCount = 0,
            skippedStageCount = 0,
            bundleSessionIds = listOf("audit-session", "default-session"),
        )

    private fun stage(
        key: String,
        label: String,
        profileId: String,
        sessionId: String,
        status: DiagnosticsHomeCompositeStageStatus,
        recommendationContributor: Boolean = false,
    ): DiagnosticsHomeCompositeStageSummary =
        DiagnosticsHomeCompositeStageSummary(
            stageKey = key,
            stageLabel = label,
            profileId = profileId,
            pathMode = ScanPathMode.RAW_PATH,
            sessionId = sessionId,
            status = status,
            headline = label,
            summary = "$label summary",
            recommendationContributor = recommendationContributor,
        )

    private fun completedSession(
        id: String,
        summary: String = "Verification complete",
    ): DiagnosticScanSession =
        DiagnosticScanSession(
            id = id,
            profileId = "default",
            pathMode = ScanPathMode.IN_PATH.name,
            serviceMode = "VPN",
            status = "completed",
            summary = summary,
            startedAt = 10L,
            finishedAt = 20L,
        )
}
