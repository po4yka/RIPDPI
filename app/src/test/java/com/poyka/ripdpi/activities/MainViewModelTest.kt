package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

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
            assertEquals(PermissionStatus.RequiresSettings, viewModel.uiState.value.permissionSummary.snapshot.notifications)
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

                assertEquals(MainEffect.OpenVpnPermissionScreen, awaitItem())
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
            assertEquals(PermissionRecovery.OpenVpnPermissionScreen, issue?.recovery)
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
                viewModel.uiState.value.permissionSummary.recommendedIssue?.kind,
            )
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
                assertEquals(MainEffect.OpenVpnPermissionScreen, awaitItem())

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
            val strategyId = deriveBypassStrategySignature(settings = settings, routeGroup = null, modeOverride = Mode.VPN).stableId()
            val diagnosticsManager =
                FakeMainDiagnosticsManager().apply {
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
                    diagnosticsManager = diagnosticsManager,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val summary = viewModel.uiState.value.approachSummary

            assertEquals("VPN Split", summary?.title)
            assertEquals("Validated", summary?.verification)
            assertEquals("75%", summary?.successRate)
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
        diagnosticsManager: FakeMainDiagnosticsManager = FakeMainDiagnosticsManager(),
    ): MainViewModel =
        MainViewModel(
            appContext = RuntimeEnvironment.getApplication(),
            appSettingsRepository = appSettingsRepository,
            serviceStateStore = serviceStateStore,
            serviceController = serviceController,
            diagnosticsManager = diagnosticsManager,
            stringResolver = FakeStringResolver(),
            trafficStatsReader = FakeTrafficStatsReader(),
            permissionStatusProvider = permissionStatusProvider,
            permissionCoordinator = PermissionCoordinator(),
        )
}

private class FakeMainDiagnosticsManager : com.poyka.ripdpi.diagnostics.DiagnosticsManager {
    override val activeScanProgress = kotlinx.coroutines.flow.MutableStateFlow<com.poyka.ripdpi.diagnostics.ScanProgress?>(null)
    override val profiles = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity>())
    override val sessions = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.ScanSessionEntity>())
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
    override val snapshots = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity>())
    override val contexts = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity>())
    override val telemetry = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity>())
    override val nativeEvents = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>())
    override val exports = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.ExportRecordEntity>())

    override suspend fun initialize() = Unit

    override suspend fun startScan(pathMode: com.poyka.ripdpi.diagnostics.ScanPathMode): String = "session"

    override suspend fun cancelActiveScan() = Unit

    override suspend fun setActiveProfile(profileId: String) = Unit

    override suspend fun loadSessionDetail(sessionId: String): com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail {
        error("unused")
    }

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): com.poyka.ripdpi.diagnostics.BypassApproachDetail {
        error("unused")
    }

    override suspend fun buildShareSummary(sessionId: String?): com.poyka.ripdpi.diagnostics.ShareSummary {
        error("unused")
    }

    override suspend fun createArchive(sessionId: String?): com.poyka.ripdpi.diagnostics.DiagnosticsArchive {
        error("unused")
    }
}
