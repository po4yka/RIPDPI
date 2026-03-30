package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeAuditOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.diagnostics.stableId
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `calculateTransferredBytes clamps negative deltas`() {
        assertEquals(0L, calculateTransferredBytes(totalBytes = 128L, baselineBytes = 256L))
    }

    @Test
    fun `calculateTransferredBytes returns positive deltas`() {
        assertEquals(512L, calculateTransferredBytes(totalBytes = 1_024L, baselineBytes = 512L))
    }

    @Test
    fun `connection metrics polling only runs while connected`() {
        assertFalse(shouldPollConnectionMetrics(ConnectionState.Disconnected))
        assertFalse(shouldPollConnectionMetrics(ConnectionState.Connecting))
        assertTrue(shouldPollConnectionMetrics(ConnectionState.Connected))
        assertFalse(shouldPollConnectionMetrics(ConnectionState.Error))
    }

    @Test
    fun `startup destination prefers onboarding until it is completed`() {
        val settings = AppSettings.newBuilder().build()

        assertEquals(Route.Onboarding.route, resolveStartupDestination(settings))
    }

    @Test
    fun `startup destination opens biometric gate after onboarding`() {
        val settings =
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .setBiometricEnabled(true)
                .build()

        assertEquals(Route.BiometricPrompt.route, resolveStartupDestination(settings))
    }

    @Test
    fun `startup destination opens home when onboarding is complete without biometrics`() {
        val settings =
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .build()

        assertEquals(Route.Home.route, resolveStartupDestination(settings))
    }

    @Test
    fun `initialize is explicit and idempotent`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val permissionStatusProvider = FakePermissionStatusProvider()
            val viewModel =
                createViewModel(
                    serviceStateStore = serviceStateStore,
                    permissionStatusProvider = permissionStatusProvider,
                    initialize = false,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            advanceUntilIdle()

            assertEquals(0, permissionStatusProvider.currentSnapshotCalls)
            // The combine maps Running + Disconnected -> Connecting even
            // before initialize(), because uiState is a reactive flow.
            assertEquals(ConnectionState.Connecting, viewModel.uiState.value.connectionState)

            viewModel.initialize()
            runCurrent()

            assertTrue(permissionStatusProvider.currentSnapshotCalls > 0)
            assertEquals(ConnectionState.Connected, viewModel.uiState.value.connectionState)

            val snapshotCallsAfterFirstInitialize = permissionStatusProvider.currentSnapshotCalls
            viewModel.initialize()
            runCurrent()

            assertEquals(snapshotCallsAfterFirstInitialize, permissionStatusProvider.currentSnapshotCalls)
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            collector.cancel()
        }

    @Test
    fun `start in vpn mode with granted permissions starts immediately`() =
        runTest {
            val serviceController = FakeServiceController()
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    permissionStatusProvider =
                        FakePermissionStatusProvider(
                            snapshot =
                                PermissionSnapshot(
                                    vpnConsent = PermissionStatus.Granted,
                                    notifications = PermissionStatus.Granted,
                                    batteryOptimization = PermissionStatus.Granted,
                                ),
                        ),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onPrimaryConnectionAction()
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            assertNull(viewModel.uiState.value.permissionSummary.issue)
            collector.cancel()
        }

    @Test
    fun `missing notifications requests notifications first`() =
        runTest {
            val serviceController = FakeServiceController()
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    permissionStatusProvider =
                        FakePermissionStatusProvider(
                            snapshot =
                                PermissionSnapshot(
                                    vpnConsent = PermissionStatus.Granted,
                                    notifications = PermissionStatus.RequiresSystemPrompt,
                                    batteryOptimization = PermissionStatus.Granted,
                                ),
                        ),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onPrimaryConnectionAction()

                val effect = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.Notifications, effect.kind)
                assertTrue(serviceController.startedModes.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
            collector.cancel()
        }

    @Test
    fun `notification denial blocks start and surfaces recovery state`() =
        runTest {
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.RequiresSystemPrompt,
                            batteryOptimization = PermissionStatus.Granted,
                        ),
                )
            val viewModel = createViewModel(permissionStatusProvider = provider)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onPrimaryConnectionAction()
            viewModel.onPermissionResult(PermissionKind.Notifications, PermissionResult.DeniedPermanently)
            advanceUntilIdle()

            val issue = viewModel.uiState.value.permissionSummary.issue
            assertEquals(PermissionKind.Notifications, issue?.kind)
            assertEquals(PermissionRecovery.OpenSettings, issue?.recovery)
            assertEquals(
                PermissionStatus.RequiresSettings,
                viewModel.uiState.value.permissionSummary.snapshot.notifications,
            )
            collector.cancel()
        }

    @Test
    fun `missing vpn consent opens vpn screen after notifications are granted`() =
        runTest {
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.RequiresSystemPrompt,
                            batteryOptimization = PermissionStatus.Granted,
                        ),
                )
            val viewModel = createViewModel(permissionStatusProvider = provider)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onPrimaryConnectionAction()
                val first = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.Notifications, first.kind)

                provider.snapshot =
                    provider.snapshot.copy(
                        notifications = PermissionStatus.Granted,
                    )
                viewModel.onPermissionResult(PermissionKind.Notifications, PermissionResult.Granted)

                assertEquals(MainEffect.ShowVpnPermissionDialog, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            collector.cancel()
        }

    @Test
    fun `vpn denial blocks only vpn start and keeps proxy untouched`() =
        runTest {
            val serviceController = FakeServiceController()
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.Granted,
                        ),
                )
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    permissionStatusProvider = provider,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onVpnPermissionContinueRequested()
                val effect = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.VpnConsent, effect.kind)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onPermissionResult(PermissionKind.VpnConsent, PermissionResult.Denied)
            advanceUntilIdle()

            assertTrue(serviceController.startedModes.isEmpty())
            val issue = viewModel.uiState.value.permissionSummary.issue
            assertEquals(PermissionKind.VpnConsent, issue?.kind)
            assertEquals(PermissionRecovery.ShowVpnPermissionDialog, issue?.recovery)
            collector.cancel()
        }

    @Test
    fun `battery optimization missing does not block start but creates recommendation`() =
        runTest {
            val serviceController = FakeServiceController()
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    permissionStatusProvider =
                        FakePermissionStatusProvider(
                            snapshot =
                                PermissionSnapshot(
                                    vpnConsent = PermissionStatus.Granted,
                                    notifications = PermissionStatus.Granted,
                                    batteryOptimization = PermissionStatus.RequiresSettings,
                                ),
                        ),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onPrimaryConnectionAction()
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            assertEquals(
                PermissionKind.BatteryOptimization,
                viewModel.uiState.value.permissionSummary.recommendedIssue
                    ?.kind,
            )
            assertTrue(viewModel.uiState.value.permissionSummary.backgroundGuidance != null)
            collector.cancel()
        }

    @Test
    fun `permission summary keeps background guidance separate from doze recommendation`() {
        val summary =
            buildPermissionSummary(
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.Granted,
                    ),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = FakeStringResolver(),
                deviceManufacturer = "samsung",
            )

        assertNull(summary.recommendedIssue)
        assertEquals(R.string.permissions_background_activity_title.toString(), summary.backgroundGuidance?.title)
        assertEquals(
            R.string.permissions_background_activity_body_samsung.toString(),
            summary.backgroundGuidance?.message,
        )
    }

    @Test
    fun `battery optimization repair clears pending action when exemption is still missing`() =
        runTest {
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.RequiresSettings,
                        ),
                )
            val viewModel = createViewModel(permissionStatusProvider = provider)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onRepairPermissionRequested(PermissionKind.BatteryOptimization)
                val first = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.BatteryOptimization, first.kind)

                viewModel.onPermissionResult(
                    PermissionKind.BatteryOptimization,
                    PermissionResult.ReturnedFromSettings,
                )
                advanceUntilIdle()

                expectNoEvents()

                viewModel.onRepairPermissionRequested(PermissionKind.BatteryOptimization)
                val second = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.BatteryOptimization, second.kind)
                cancelAndIgnoreRemainingEvents()
            }

            collector.cancel()
        }

    @Test
    fun `pending action resumes across notifications and vpn consent`() =
        runTest {
            val serviceController = FakeServiceController()
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.RequiresSystemPrompt,
                            batteryOptimization = PermissionStatus.RequiresSettings,
                        ),
                )
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    permissionStatusProvider = provider,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onPrimaryConnectionAction()
                assertEquals(PermissionKind.Notifications, (awaitItem() as MainEffect.RequestPermission).kind)

                provider.snapshot =
                    provider.snapshot.copy(
                        notifications = PermissionStatus.Granted,
                    )
                viewModel.onPermissionResult(PermissionKind.Notifications, PermissionResult.Granted)
                assertEquals(MainEffect.ShowVpnPermissionDialog, awaitItem())

                viewModel.onVpnPermissionContinueRequested()
                assertEquals(PermissionKind.VpnConsent, (awaitItem() as MainEffect.RequestPermission).kind)

                provider.snapshot =
                    provider.snapshot.copy(
                        vpnConsent = PermissionStatus.Granted,
                    )
                viewModel.onPermissionResult(PermissionKind.VpnConsent, PermissionResult.Granted)
                advanceUntilIdle()

                assertEquals(listOf(Mode.VPN), serviceController.startedModes)
                cancelAndIgnoreRemainingEvents()
            }
            collector.cancel()
        }

    @Test
    fun `proxy mode start is not blocked by vpn consent`() =
        runTest {
            val serviceController = FakeServiceController()
            val settingsRepository =
                FakeAppSettingsRepository(
                    initialSettings =
                        AppSettings
                            .newBuilder()
                            .setOnboardingComplete(true)
                            .setRipdpiMode("proxy")
                            .build(),
                )
            val viewModel =
                createViewModel(
                    appSettingsRepository = settingsRepository,
                    serviceController = serviceController,
                    permissionStatusProvider =
                        FakePermissionStatusProvider(
                            snapshot =
                                PermissionSnapshot(
                                    vpnConsent = PermissionStatus.RequiresSystemPrompt,
                                    notifications = PermissionStatus.Granted,
                                    batteryOptimization = PermissionStatus.Granted,
                                ),
                        ),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onPrimaryConnectionAction()
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
            collector.cancel()
        }

    @Test
    fun `home state exposes current approach summary`() =
        runTest {
            val settings =
                AppSettings
                    .newBuilder()
                    .setOnboardingComplete(true)
                    .setRipdpiMode("vpn")
                    .build()
            val strategyId =
                deriveBypassStrategySignature(
                    settings = settings,
                    routeGroup = null,
                    modeOverride = Mode.VPN,
                ).stableId()
            val diagnosticsTimelineSource =
                FakeMainDiagnosticsTimelineSource().apply {
                    approachStats.value =
                        listOf(
                            BypassApproachSummary(
                                approachId = BypassApproachId(BypassApproachKind.Strategy, strategyId),
                                displayName = "VPN Split",
                                secondaryLabel = "Strategy",
                                verificationState = "validated",
                                validatedScanCount = 4,
                                validatedSuccessCount = 3,
                                validatedSuccessRate = 0.75f,
                                lastValidatedResult = "All probes passed",
                                usageCount = 5,
                                totalRuntimeDurationMs = 300_000L,
                                recentRuntimeHealth = BypassRuntimeHealthSummary(),
                                lastUsedAt = 10L,
                            ),
                        )
                }
            val viewModel =
                createViewModel(
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val summary = viewModel.uiState.value.approachSummary

            assertEquals("VPN Split", summary?.title)
            assertEquals("Validated", summary?.verification)
            assertEquals("75%", summary?.successRate)
            collector.cancel()
        }

    @Test
    fun `home full analysis starts automatic audit profile`() =
        runTest {
            val scanController =
                StubDiagnosticsScanController().apply {
                    startResults += DiagnosticsManualScanStartResult.Started("audit-session")
                }
            val diagnosticsTimelineSource = FakeMainDiagnosticsTimelineSource()
            val viewModel =
                createViewModel(
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = scanController,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            advanceUntilIdle()

            assertEquals(ScanPathMode.RAW_PATH, scanController.lastStartedPathMode)
            assertEquals("automatic-audit", scanController.lastActiveProfileId)
            assertTrue(viewModel.uiState.value.homeDiagnostics.analysisAction.busy)
            collector.cancel()
        }

    @Test
    fun `actionable home audit enables verified vpn and shares archive for selected session`() =
        runTest {
            val scanController =
                StubDiagnosticsScanController().apply {
                    startResults += DiagnosticsManualScanStartResult.Started("audit-session")
                }
            val diagnosticsTimelineSource = FakeMainDiagnosticsTimelineSource()
            val shareService = StubDiagnosticsShareService()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                    auditOutcomes["audit-session"] =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = "audit-session",
                            fingerprintHash = "fp-1",
                            actionable = true,
                            headline = "Analysis complete and settings applied",
                            summary = "Working TCP and QUIC winners found.",
                            confidenceSummary = "Confidence high",
                            coverageSummary = "Coverage 92%",
                            recommendationSummary = "TCP split + QUIC fake",
                            appliedSettings = listOf(DiagnosticsAppliedSetting("TCP/TLS lane", "Split")),
                        )
                }
            val viewModel =
                createViewModel(
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = scanController,
                    diagnosticsShareService = shareService,
                    diagnosticsHomeWorkflowService = homeWorkflowService,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            diagnosticsTimelineSource.sessions.value = listOf(completedSession("audit-session"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.homeDiagnostics.verifiedVpnAction.enabled)
            assertEquals(
                "audit-session",
                viewModel.uiState.value.homeDiagnostics.analysisSheet
                    ?.sessionId,
            )

            viewModel.effects.test {
                viewModel.onShareHomeAnalysis()

                val effect = awaitItem() as MainEffect.ShareDiagnosticsArchive
                assertEquals("audit-session", shareService.archiveRequest?.requestedSessionId)
                assertEquals(shareService.archiveResult.absolutePath, effect.absolutePath)
                assertEquals(shareService.archiveResult.fileName, effect.fileName)
                cancelAndIgnoreRemainingEvents()
            }
            collector.cancel()
        }

    @Test
    fun `network change invalidates actionable home audit result`() =
        runTest {
            val scanController =
                StubDiagnosticsScanController().apply {
                    startResults += DiagnosticsManualScanStartResult.Started("audit-session")
                }
            val diagnosticsTimelineSource = FakeMainDiagnosticsTimelineSource()
            val serviceStateStore = FakeServiceStateStore()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                    auditOutcomes["audit-session"] =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = "audit-session",
                            fingerprintHash = "fp-1",
                            actionable = true,
                            headline = "Applied",
                            summary = "Fresh result",
                        )
                }
            val viewModel =
                createViewModel(
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = scanController,
                    diagnosticsHomeWorkflowService = homeWorkflowService,
                    serviceStateStore = serviceStateStore,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            diagnosticsTimelineSource.sessions.value = listOf(completedSession("audit-session"))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.homeDiagnostics.verifiedVpnAction.enabled)

            homeWorkflowService.currentFingerprint = "fp-2"
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.homeDiagnostics.verifiedVpnAction.enabled)
            assertTrue(
                viewModel.uiState.value.homeDiagnostics.latestAudit
                    ?.stale == true,
            )
            collector.cancel()
        }

    @Test
    fun `verified vpn action starts vpn mode after actionable audit`() =
        runTest {
            val scanController =
                StubDiagnosticsScanController().apply {
                    startResults += DiagnosticsManualScanStartResult.Started("audit-session")
                }
            val diagnosticsTimelineSource = FakeMainDiagnosticsTimelineSource()
            val serviceController = FakeServiceController()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                    auditOutcomes["audit-session"] =
                        DiagnosticsHomeAuditOutcome(
                            sessionId = "audit-session",
                            fingerprintHash = "fp-1",
                            actionable = true,
                            headline = "Applied",
                            summary = "Ready to verify",
                        )
                }
            val viewModel =
                createViewModel(
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = scanController,
                    diagnosticsHomeWorkflowService = homeWorkflowService,
                    serviceController = serviceController,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            diagnosticsTimelineSource.sessions.value = listOf(completedSession("audit-session"))
            advanceUntilIdle()

            viewModel.onStartVerifiedVpn()
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            collector.cancel()
        }

    private fun createViewModel(
        appSettingsRepository: FakeAppSettingsRepository =
            FakeAppSettingsRepository(
                initialSettings =
                    AppSettings
                        .newBuilder()
                        .setOnboardingComplete(true)
                        .setRipdpiMode("vpn")
                        .build(),
            ),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        serviceController: FakeServiceController = FakeServiceController(),
        permissionStatusProvider: FakePermissionStatusProvider = FakePermissionStatusProvider(),
        diagnosticsTimelineSource: FakeMainDiagnosticsTimelineSource = FakeMainDiagnosticsTimelineSource(),
        diagnosticsScanController: StubDiagnosticsScanController = StubDiagnosticsScanController(),
        diagnosticsShareService: StubDiagnosticsShareService = StubDiagnosticsShareService(),
        diagnosticsHomeWorkflowService: StubDiagnosticsHomeWorkflowService = StubDiagnosticsHomeWorkflowService(),
        initialize: Boolean = true,
    ): MainViewModel =
        MainViewModel(
            appSettingsRepository = appSettingsRepository,
            serviceStateStore = serviceStateStore,
            serviceController = serviceController,
            diagnosticsTimelineSource = diagnosticsTimelineSource,
            diagnosticsScanController = diagnosticsScanController,
            diagnosticsShareService = diagnosticsShareService,
            diagnosticsHomeWorkflowService = diagnosticsHomeWorkflowService,
            stringResolver = FakeStringResolver(),
            trafficStatsReader = FakeTrafficStatsReader(),
            permissionPlatformBridge = FakePermissionPlatformBridge(),
            permissionStatusProvider = permissionStatusProvider,
            permissionCoordinator = PermissionCoordinator(),
            crashReportReader =
                com.poyka.ripdpi.diagnostics.crash.CrashReportReader(
                    java.io.File(System.getProperty("java.io.tmpdir"), "ripdpi-test-crash-reports"),
                ),
        ).also { viewModel ->
            if (initialize) {
                viewModel.initialize()
            }
        }

    private fun grantedPermissionStatusProvider(): FakePermissionStatusProvider =
        FakePermissionStatusProvider(
            snapshot =
                PermissionSnapshot(
                    vpnConsent = PermissionStatus.Granted,
                    notifications = PermissionStatus.Granted,
                    batteryOptimization = PermissionStatus.Granted,
                ),
        )

    private fun completedSession(
        id: String,
        summary: String = "Completed",
        pathMode: ScanPathMode = ScanPathMode.RAW_PATH,
    ): DiagnosticScanSession =
        DiagnosticScanSession(
            id = id,
            profileId = "automatic-audit",
            pathMode = pathMode.name,
            serviceMode = "VPN",
            status = "completed",
            summary = summary,
            startedAt = 10L,
            finishedAt = 20L,
        )
}

private class FakeMainDiagnosticsTimelineSource : com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource {
    override val activeScanProgress =
        kotlinx.coroutines.flow.MutableStateFlow<com.poyka.ripdpi.diagnostics.ScanProgress?>(null)
    override val activeConnectionSession =
        kotlinx.coroutines.flow.MutableStateFlow<com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession?>(null)
    override val profiles =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticProfile>())
    override val sessions =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticScanSession>())
    override val approachStats =
        kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                BypassApproachSummary(
                    approachId = BypassApproachId(BypassApproachKind.Strategy, "strategy-a"),
                    displayName = "VPN Split",
                    secondaryLabel = "Strategy",
                    verificationState = "validated",
                    validatedScanCount = 2,
                    validatedSuccessCount = 2,
                    validatedSuccessRate = 1f,
                    lastValidatedResult = "All probes passed",
                    usageCount = 3,
                    totalRuntimeDurationMs = 120_000L,
                    recentRuntimeHealth = BypassRuntimeHealthSummary(),
                    lastUsedAt = 1L,
                ),
            ),
        )
    override val snapshots =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot>())
    override val contexts =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot>())
    override val telemetry =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample>())
    override val nativeEvents =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticEvent>())
    override val liveSnapshots =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot>())
    override val liveContexts =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot>())
    override val liveTelemetry =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample>())
    override val liveNativeEvents =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticEvent>())
    override val exports =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.diagnostics.DiagnosticExportRecord>())
}
