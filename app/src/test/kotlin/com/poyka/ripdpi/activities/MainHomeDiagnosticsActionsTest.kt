package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveFailureCode
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeProgress
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectionReason
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
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

    @Suppress("LongMethod")
    @Test
    fun `verified vpn flow starts in-path verification scan`() =
        runTest {
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
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
                    latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
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
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
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
            assertTrue(effects.replayCache.isEmpty())
        }

    @Test
    fun `run full analysis surfaces start failures`() =
        runTest {
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
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
            assertTrue(homeDiagnosticsState.value.analysisStartFailed)
        }

    @Test
    fun `cancel analysis stops the active composite run and exposes cancelled status`() =
        runTest {
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            runCurrent()
            actions.cancelAnalysis()
            runCurrent()

            assertEquals(listOf("home-run"), compositeRunService.cancelledRunIds)
            assertEquals(
                DiagnosticsHomeCompositeRunStatus.CANCELLED,
                homeDiagnosticsState.value.activeRunProgress?.status,
            )
            assertNull(homeDiagnosticsState.value.activeRunId)
        }

    @Test
    fun `start verified vpn blocks stale composite results`() =
        runTest {
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
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
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
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
            assertTrue(effects.replayCache.isEmpty())
        }

    @Test
    fun `verified vpn flow clears waiting when notifications permission blocks startup`() =
        runTest {
            val permissionState =
                MutableStateFlow(
                    PermissionRuntimeState(
                        snapshot =
                            PermissionSnapshot(
                                vpnConsent = PermissionStatus.Granted,
                                notifications = PermissionStatus.RequiresSystemPrompt,
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
                        verificationProgress = "Starting VPN",
                    ),
                )
            val actions =
                createActions(
                    scope = backgroundScope,
                    permissionState = permissionState,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.initialize()
            advanceUntilIdle()
            permissionState.value =
                permissionState.value.copy(
                    issue =
                        PermissionIssueUiState(
                            kind = PermissionKind.Notifications,
                            title = "Notifications required",
                            message = "Allow notifications to start the foreground service.",
                            recovery = PermissionRecovery.RetryPrompt,
                            actionLabel = "Allow",
                            blocking = true,
                        ),
                )
            runCurrent()
            advanceUntilIdle()

            assertFalse(homeDiagnosticsState.value.waitingForVerifiedVpnStart)
            assertNull(homeDiagnosticsState.value.verificationProgress)
            assertEquals("Notifications required", homeDiagnosticsState.value.verificationSheet?.headline)
            assertEquals(
                "Allow notifications to start the foreground service.",
                homeDiagnosticsState.value.verificationSheet?.summary,
            )
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
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
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
            val effect = effects.replayCache.firstOrNull()
            assertTrue(
                "expected ShowError effect but got $effect",
                effect is MainEffect.ShowError,
            )
        }

    @Test
    fun `buildHomeDiagnosticsUiState maps active run progress to analysisProgress`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime =
                    HomeDiagnosticsRuntimeState(
                        activeRunProgress =
                            DiagnosticsHomeCompositeProgress(
                                runId = "run-1",
                                status = DiagnosticsHomeCompositeRunStatus.RUNNING,
                                activeStageIndex = 1,
                                stages =
                                    listOf(
                                        stage(
                                            key = "tcp_parser",
                                            label = "TCP Parser-only",
                                            profileId = "tcp-parser",
                                            sessionId = "s1",
                                            status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                        ),
                                        stage(
                                            key = "tcp_desync",
                                            label = "TCP Desync",
                                            profileId = "tcp-desync",
                                            sessionId = "s2",
                                            status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                                        ),
                                        stage(
                                            key = "udp_default",
                                            label = "UDP Default",
                                            profileId = "udp-default",
                                            sessionId = "s3",
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                        ),
                                        stage(
                                            key = "skipped_stage",
                                            label = "Skipped",
                                            profileId = "skipped",
                                            sessionId = "s4",
                                            status = DiagnosticsHomeCompositeStageStatus.SKIPPED,
                                        ),
                                    ),
                            ),
                    ),
                stringResolver = FakeStringResolver(),
            )

        val progress = uiState.analysisProgress
        assertTrue("analysisProgress should be non-null when busy", progress != null)
        assertEquals(4, progress!!.stages.size)
        assertEquals(1, progress.activeStageIndex)
        assertEquals(AnalysisStageStatus.COMPLETED, progress.stages[0].status)
        assertEquals(AnalysisStageStatus.RUNNING, progress.stages[1].status)
        assertEquals(AnalysisStageStatus.PENDING, progress.stages[2].status)
        assertEquals(AnalysisStageStatus.COMPLETED, progress.stages[3].status)
        assertEquals(
            "${R.string.home_diagnostics_stage_counter}:2,4 · TCP Desync",
            uiState.analysisAction.supportingText,
        )
    }

    @Test
    fun `buildHomeDiagnosticsUiState treats start admission as busy`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime = HomeDiagnosticsRuntimeState(analysisStarting = true),
                stringResolver = FakeStringResolver(),
            )

        assertTrue(uiState.analysisAction.busy)
        assertFalse(uiState.analysisAction.enabled)
        assertEquals(HomeDiagnosticsRunUiStatus.STARTING, uiState.analysisRunStatus)
        assertEquals(
            R.string.home_diagnostics_analysis_starting.toString(),
            uiState.analysisAction.supportingText,
        )
    }

    @Test
    fun `buildHomeDiagnosticsUiState maps failed and unavailable stages correctly`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime =
                    HomeDiagnosticsRuntimeState(
                        activeRunProgress =
                            DiagnosticsHomeCompositeProgress(
                                runId = "run-2",
                                status = DiagnosticsHomeCompositeRunStatus.RUNNING,
                                activeStageIndex = 2,
                                stages =
                                    listOf(
                                        stage(
                                            key = "s1",
                                            label = "Stage 1",
                                            profileId = "p1",
                                            sessionId = "s1",
                                            status = DiagnosticsHomeCompositeStageStatus.FAILED,
                                        ),
                                        stage(
                                            key = "s2",
                                            label = "Stage 2",
                                            profileId = "p2",
                                            sessionId = "s2",
                                            status = DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                                        ),
                                        stage(
                                            key = "s3",
                                            label = "Stage 3",
                                            profileId = "p3",
                                            sessionId = "s3",
                                            status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                                        ),
                                    ),
                            ),
                    ),
                stringResolver = FakeStringResolver(),
            )

        val progress = uiState.analysisProgress
        assertTrue("analysisProgress should be non-null", progress != null)
        assertEquals(AnalysisStageStatus.FAILED, progress!!.stages[0].status)
        assertEquals(AnalysisStageStatus.FAILED, progress.stages[1].status)
        assertEquals(AnalysisStageStatus.RUNNING, progress.stages[2].status)
    }

    @Test
    fun `buildHomeDiagnosticsUiState returns null analysisProgress when not busy`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime = HomeDiagnosticsRuntimeState(),
                stringResolver = FakeStringResolver(),
            )

        assertNull(uiState.analysisProgress)
    }

    @Test
    fun `buildHomeDiagnosticsUiState exposes capability evidence on the analysis sheet`() {
        val uiState =
            buildHomeDiagnosticsUiState(
                settings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                runtime =
                    HomeDiagnosticsRuntimeState(
                        latestCompositeOutcome =
                            compositeOutcome().copy(
                                capabilityEvidence =
                                    listOf(
                                        DiagnosticsCapabilityEvidence(
                                            authority = "video.example",
                                            summary = "QUIC usable · UDP usable",
                                            details =
                                                listOf(
                                                    com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting(
                                                        "QUIC",
                                                        "Available",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        analysisSheetVisible = true,
                    ),
                stringResolver = FakeStringResolver(),
            )

        assertEquals(
            "video.example",
            uiState.analysisSheet
                ?.capabilityEvidence
                ?.single()
                ?.authority,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainHomeDiagnosticsConcurrencyTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `cancel failure preserves the active run and reports a cancellation error`() =
        runTest {
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
            val compositeRunService =
                StubDiagnosticsHomeCompositeRunService().apply {
                    cancelFailure = IllegalStateException("cancel failed")
                }
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    effects = effects,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            runCurrent()
            compositeRunService.emitProgress(
                progress = compositeRunService.runningProgress(),
            )
            runCurrent()
            actions.cancelAnalysis()
            runCurrent()

            assertEquals("home-run", homeDiagnosticsState.value.activeRunId)
            assertEquals(
                DiagnosticsHomeCompositeRunStatus.RUNNING,
                homeDiagnosticsState.value.activeRunProgress?.status,
            )
            assertFalse(homeDiagnosticsState.value.analysisStartFailed)
            assertEquals(
                "Couldn’t cancel diagnostics. The scan may still be running.",
                (effects.replayCache.single() as MainEffect.ShowError).message,
            )
        }

    @Test
    fun `starting analysis blocks a conflicting second start`() =
        runTest {
            val startGate = CompletableDeferred<Unit>()
            val compositeRunService = StubDiagnosticsHomeCompositeRunService().apply { this.startGate = startGate }
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            runCurrent()
            assertTrue(homeDiagnosticsState.value.analysisStarting)

            actions.runFullAnalysis()
            runCurrent()
            assertEquals(listOf("home-run"), compositeRunService.startedRunIds)

            startGate.complete(Unit)
            runCurrent()
            assertFalse(homeDiagnosticsState.value.analysisStarting)
            assertEquals("home-run", homeDiagnosticsState.value.activeRunId)
        }

    @Test
    fun `cancel analysis aborts a stuck start before a run id exists`() =
        runTest {
            val startGate = CompletableDeferred<Unit>()
            val compositeRunService = StubDiagnosticsHomeCompositeRunService().apply { this.startGate = startGate }
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            runCurrent()
            assertTrue(homeDiagnosticsState.value.analysisStarting)

            actions.cancelAnalysis()
            runCurrent()

            assertFalse(homeDiagnosticsState.value.analysisStarting)
            assertTrue(homeDiagnosticsState.value.analysisStartCancelled)
            assertNull(homeDiagnosticsState.value.activeRunId)
            assertTrue(compositeRunService.cancelledRunIds.isEmpty())
        }

    @Test
    fun `stuck analysis start reaches a bounded failure state`() =
        runTest {
            val startGate = CompletableDeferred<Unit>()
            val compositeRunService = StubDiagnosticsHomeCompositeRunService().apply { this.startGate = startGate }
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )

            actions.runFullAnalysis()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertFalse(homeDiagnosticsState.value.analysisStarting)
            assertTrue(homeDiagnosticsState.value.analysisStartFailed)
            assertNull(homeDiagnosticsState.value.activeRunId)
        }

    @Test
    fun `stage progress resets when the active scan session changes`() =
        runTest {
            val timeline = StubDiagnosticsTimelineSource()
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    diagnosticsTimelineSource = timeline,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )
            actions.initialize()
            actions.runFullAnalysis()
            runCurrent()

            val runFlow = requireNotNull(compositeRunService.runs["home-run"])
            runFlow.value =
                DiagnosticsHomeCompositeProgress(
                    runId = "home-run",
                    activeStageIndex = 0,
                    activeSessionId = "session-one",
                    stages =
                        listOf(
                            stage(
                                key = "one",
                                label = "One",
                                profileId = "one",
                                sessionId = "session-one",
                                status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                            ),
                        ),
                )
            runCurrent()
            timeline.activeScanProgress.value =
                com.poyka.ripdpi.diagnostics.ScanProgress(
                    sessionId = "session-one",
                    phase = "running",
                    completedSteps = 8,
                    totalSteps = 10,
                    message = "Testing one",
                )
            runCurrent()
            assertEquals(0.8f, homeDiagnosticsState.value.activeStageStepProgress, 0.001f)

            runFlow.value =
                runFlow.value.copy(
                    activeStageIndex = 1,
                    activeSessionId = "session-two",
                    stages =
                        runFlow.value.stages +
                            stage(
                                key = "two",
                                label = "Two",
                                profileId = "two",
                                sessionId = "session-two",
                                status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                            ),
                )
            runCurrent()

            assertEquals(0f, homeDiagnosticsState.value.activeStageStepProgress, 0.001f)
            assertEquals(null, homeDiagnosticsState.value.activeRunStageProgress)
        }

    @Test
    fun `visible sibling progress becomes the active parallel run stage`() =
        runTest {
            val timeline = StubDiagnosticsTimelineSource()
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
            val actions =
                createActions(
                    scope = backgroundScope,
                    diagnosticsTimelineSource = timeline,
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    homeDiagnosticsState = homeDiagnosticsState,
                )
            actions.initialize()
            actions.runFullAnalysis()
            runCurrent()

            val runFlow = requireNotNull(compositeRunService.runs["home-run"])
            runFlow.value =
                DiagnosticsHomeCompositeProgress(
                    runId = "home-run",
                    activeStageIndex = 0,
                    activeSessionId = "session-one",
                    stages =
                        listOf(
                            stage(
                                key = "one",
                                label = "One",
                                profileId = "one",
                                sessionId = "session-one",
                                status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                            ),
                            stage(
                                key = "two",
                                label = "Two",
                                profileId = "two",
                                sessionId = "session-two",
                                status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                            ),
                        ),
                )
            runCurrent()

            timeline.activeScanProgress.value =
                com.poyka.ripdpi.diagnostics.ScanProgress(
                    sessionId = "session-two",
                    phase = "running",
                    completedSteps = 2,
                    totalSteps = 5,
                    message = "Testing two",
                )
            runCurrent()

            val state = homeDiagnosticsState.value
            assertEquals("session-two", state.activeRunProgress?.activeSessionId)
            assertEquals(1, state.activeRunProgress?.activeStageIndex)
            assertEquals("Testing two", state.activeRunStageProgress)
            assertEquals(0.4f, state.activeStageStepProgress, 0.001f)
            assertFalse(state.externalScanActive)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainHomeDiagnosticsShareFailureTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `share failure exposes only its stable support code`() =
        runTest {
            val effects = MutableSharedFlow<MainEffect>(replay = 16, extraBufferCapacity = 16)
            val shareService =
                StubDiagnosticsShareService().apply {
                    archiveFailure =
                        DiagnosticsArchiveException(
                            DiagnosticsArchiveFailureCode.DATABASE,
                            IllegalStateException("sensitive database detail"),
                        )
                }
            val actions =
                createActions(
                    scope = backgroundScope,
                    effects = effects,
                    diagnosticsShareService = shareService,
                    homeDiagnosticsState =
                        MutableStateFlow(
                            HomeDiagnosticsRuntimeState(
                                latestCompositeOutcome = compositeOutcome(),
                            ),
                        ),
                )

            actions.shareLatestHomeAnalysis()
            runCurrent()
            advanceUntilIdle()

            assertEquals(
                "archive_database",
                (effects.replayCache.single() as MainEffect.ShowError).supportCode,
            )
        }
}

private fun createActions(
    scope: CoroutineScope,
    effects: MutableSharedFlow<MainEffect> = MutableSharedFlow(replay = 16, extraBufferCapacity = 16),
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
        latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
        runtimeState = runtimeState,
        permissionState = permissionState,
        homeDiagnosticsState = homeDiagnosticsState,
        stringResolver = ResourceStringResolver(),
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
