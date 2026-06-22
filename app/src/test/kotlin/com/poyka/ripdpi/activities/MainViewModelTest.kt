package com.poyka.ripdpi.activities

import android.os.SystemClock
import app.cash.turbine.test
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeAuditOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
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
import com.poyka.ripdpi.services.AndroidHardKillSwitchSnapshot
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@Suppress("LargeClass")
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

        assertEquals(Route.Onboarding, resolveStartupDestination(settings))
    }

    @Test
    fun `effective connection state reconciles service and runtime state`() {
        assertEquals(
            ConnectionState.Connecting,
            resolveEffectiveConnectionState(
                appStatus = AppStatus.Running,
                runtimeConnectionState = ConnectionState.Disconnected,
            ),
        )
        assertEquals(
            ConnectionState.Disconnected,
            resolveEffectiveConnectionState(
                appStatus = AppStatus.Halted,
                runtimeConnectionState = ConnectionState.Connected,
            ),
        )
        assertEquals(
            ConnectionState.Error,
            resolveEffectiveConnectionState(
                appStatus = AppStatus.Halted,
                runtimeConnectionState = ConnectionState.Error,
            ),
        )
    }

    @Test
    fun `primary connection action resolves from ui state`() {
        assertEquals(
            MainPrimaryConnectionAction.NONE,
            resolvePrimaryConnectionAction(
                connectionState = ConnectionState.Connecting,
                appStatus = AppStatus.Running,
            ),
        )
        assertEquals(
            MainPrimaryConnectionAction.STOP,
            resolvePrimaryConnectionAction(
                connectionState = ConnectionState.Connected,
                appStatus = AppStatus.Running,
            ),
        )
        assertEquals(
            MainPrimaryConnectionAction.START_CONFIGURED_MODE,
            resolvePrimaryConnectionAction(
                connectionState = ConnectionState.Disconnected,
                appStatus = AppStatus.Halted,
            ),
        )
    }

    @Test
    fun `connection actuator maps disconnected state to open pending pipeline`() {
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Disconnected,
                runtime = ConnectionRuntimeState(),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = FakeStringResolver(),
            )

        assertEquals(HomeConnectionActuatorStatus.Open, state.status)
        assertEquals(0f, state.carriageFraction)
        assertTrue(state.stages.all { it.state == HomeConnectionActuatorStageState.Pending })
    }

    @Test
    fun `connection actuator maps connecting state to synthetic active stage`() {
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connecting,
                runtime =
                    ConnectionRuntimeState(
                        connectionState = ConnectionState.Connecting,
                        connectingStartedAtMs = SystemClock.elapsedRealtime() - 2_500L,
                    ),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = FakeStringResolver(),
            )

        assertEquals(HomeConnectionActuatorStatus.Engaging, state.status)
        assertTrue(state.carriageFraction in 0.01f..0.99f)
        assertTrue(state.stages.any { it.state == HomeConnectionActuatorStageState.Active })
    }

    @Test
    fun `connection actuator maps connected state to locked complete pipeline`() {
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.Proxy,
                connectionState = ConnectionState.Connected,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = FakeStringResolver(),
            )

        assertEquals(HomeConnectionActuatorStatus.Locked, state.status)
        assertEquals(1f, state.carriageFraction)
        assertTrue(state.stages.all { it.state == HomeConnectionActuatorStageState.Complete })
    }

    @Test
    fun `connection actuator labels relay backed vpn as vpn`() {
        val stringResolver = ResourceStringResolver()
        val directVpn =
            buildConnectionActuatorUiState(
                settings =
                    AppSettings
                        .newBuilder()
                        .setRelayEnabled(false)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Disconnected,
                runtime = ConnectionRuntimeState(),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = stringResolver,
            )
        val relayBackedVpn =
            buildConnectionActuatorUiState(
                settings =
                    AppSettings
                        .newBuilder()
                        .setRelayEnabled(true)
                        .setRelayKind(RelayKindVlessReality)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Disconnected,
                runtime = ConnectionRuntimeState(),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = stringResolver,
            )

        assertEquals("Local VPN", directVpn.routeLabel)
        assertEquals("VPN", relayBackedVpn.routeLabel)
    }

    @Test
    fun `connection actuator marks direct egress as Direct not Secure`() {
        val stringResolver = ResourceStringResolver()
        val state =
            buildConnectionActuatorUiState(
                settings =
                    AppSettings
                        .newBuilder()
                        .setRelayEnabled(false)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = stringResolver,
            )

        assertEquals("Direct", state.trailingLabel)
        assertEquals("Direct line locked", state.statusDescription)
    }

    @Test
    fun `connection actuator marks live relay egress as Secure`() {
        val stringResolver = ResourceStringResolver()
        val state =
            buildConnectionActuatorUiState(
                settings =
                    AppSettings
                        .newBuilder()
                        .setRelayEnabled(true)
                        .setRelayKind(RelayKindVlessReality)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry =
                    ServiceTelemetrySnapshot(
                        relayTelemetry =
                            NativeRuntimeSnapshot.idle(source = "relay").copy(
                                state = "running",
                                activeSessions = 1,
                            ),
                    ),
                approachSummary = null,
                stringResolver = stringResolver,
            )

        assertEquals("Secure", state.trailingLabel)
        assertEquals("Secure line locked", state.statusDescription)
    }

    @Test
    fun `connection actuator treats relay fallback to direct as Direct`() {
        // relayEnabled=true in settings but relayTelemetry is idle (0 sessions) →
        // isRelayBackedEgress returns false, so the label must read "Direct".
        val stringResolver = ResourceStringResolver()
        val state =
            buildConnectionActuatorUiState(
                settings =
                    AppSettings
                        .newBuilder()
                        .setRelayEnabled(true)
                        .setRelayKind(RelayKindVlessReality)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry = ServiceTelemetrySnapshot(), // relayTelemetry stays idle, 0 sessions
                approachSummary = null,
                stringResolver = stringResolver,
            )

        assertEquals("Direct", state.trailingLabel)
        assertEquals("Direct line locked", state.statusDescription)
    }

    @Test
    fun `hard kill switch reducer surfaces observed lockdown states`() {
        val stringResolver = FakeStringResolver()
        val notEnabled =
            buildHardKillSwitchUiState(
                snapshot =
                    AndroidHardKillSwitchSnapshot(
                        status = AndroidHardKillSwitchStatus.NOT_ENABLED,
                        alwaysOn = true,
                        lockdown = false,
                    ),
                configuredMode = Mode.VPN,
                activeMode = Mode.VPN,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                stringResolver = stringResolver,
            )
        val unknown =
            buildHardKillSwitchUiState(
                snapshot = AndroidHardKillSwitchSnapshot(),
                configuredMode = Mode.VPN,
                activeMode = Mode.VPN,
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
                stringResolver = stringResolver,
            )

        assertTrue(notEnabled.visible)
        assertEquals(
            stringResolver.getString(R.string.home_hard_kill_switch_not_enabled_title),
            notEnabled.label,
        )
        assertEquals(
            stringResolver.getString(R.string.home_hard_kill_switch_unknown_title),
            unknown.label,
        )
    }

    @Test
    fun `connection actuator disables deactivation when Android lockdown owns VPN lifecycle`() {
        val stringResolver = FakeStringResolver()
        val hardKillSwitch =
            buildHardKillSwitchUiState(
                snapshot =
                    AndroidHardKillSwitchSnapshot(
                        status = AndroidHardKillSwitchStatus.ENABLED,
                        alwaysOn = true,
                        lockdown = true,
                    ),
                configuredMode = Mode.VPN,
                activeMode = Mode.VPN,
                appStatus = AppStatus.Running,
                connectionState = ConnectionState.Connected,
                stringResolver = stringResolver,
            )
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                hardKillSwitch = hardKillSwitch,
                stringResolver = stringResolver,
            )

        assertFalse(state.isDeactivationAvailable)
        assertEquals(
            stringResolver.getString(R.string.home_connection_actuator_action_android_managed),
            state.actionLabel,
        )
    }

    @Test
    fun `connection actuator localizes error to tunnel by default`() {
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Error,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Error),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = FakeStringResolver(),
            )

        assertEquals(HomeConnectionActuatorStatus.Fault, state.status)
        assertEquals(
            HomeConnectionActuatorStageState.Failed,
            state.stages.single { it.stage == HomeConnectionActuatorStage.Tunnel }.state,
        )
        assertTrue(state.carriageFraction < 1f)
    }

    @Test
    fun `connection actuator keeps degraded sessions locked with localized warning`() {
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry =
                    ServiceTelemetrySnapshot(
                        tunnelTelemetry =
                            NativeRuntimeSnapshot
                                .idle(source = "tunnel")
                                .copy(resolverFallbackActive = true),
                    ),
                approachSummary = null,
                stringResolver = FakeStringResolver(),
            )

        assertEquals(HomeConnectionActuatorStatus.Degraded, state.status)
        assertEquals(1f, state.carriageFraction)
        assertEquals(
            HomeConnectionActuatorStageState.Warning,
            state.stages.single { it.stage == HomeConnectionActuatorStage.Dns }.state,
        )
    }

    @Test
    fun `connection actuator maps DNS failure class to DNS fault`() {
        val state =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Error,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Error),
                telemetry =
                    ServiceTelemetrySnapshot(
                        runtimeFieldTelemetry =
                            RuntimeFieldTelemetry(
                                failureClass = FailureClass.DnsInterference,
                            ),
                    ),
                approachSummary = null,
                stringResolver = FakeStringResolver(),
            )

        assertEquals(
            HomeConnectionActuatorStageState.Failed,
            state.stages.single { it.stage == HomeConnectionActuatorStage.Dns }.state,
        )
    }

    @Test
    fun `startup destination opens biometric gate after onboarding`() {
        val settings =
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .setBiometricEnabled(true)
                .build()

        assertEquals(Route.BiometricPrompt, resolveStartupDestination(settings))
    }

    @Test
    fun `startup destination opens home when onboarding is complete without biometrics`() {
        val settings =
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .build()

        assertEquals(Route.Home, resolveStartupDestination(settings))
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
    fun `missing notifications do not block start`() =
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

            viewModel.onPrimaryConnectionAction()
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            collector.cancel()
        }

    @Test
    fun `notification denial from explicit request surfaces recovery state`() =
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

            viewModel.effects.test {
                viewModel.onRepairPermissionRequested(PermissionKind.Notifications)
                val effect = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.Notifications, effect.kind)

                viewModel.onPermissionResult(PermissionKind.Notifications, PermissionResult.DeniedPermanently)
                cancelAndIgnoreRemainingEvents()
            }
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
    fun `missing vpn consent opens vpn screen even when notifications are missing`() =
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
    fun `vpn consent repair updates permission without starting vpn`() =
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
                viewModel.onRepairPermissionRequested(PermissionKind.VpnConsent)
                val effect = awaitItem() as MainEffect.RequestPermission
                assertEquals(PermissionKind.VpnConsent, effect.kind)

                provider.snapshot =
                    provider.snapshot.copy(
                        vpnConsent = PermissionStatus.Granted,
                    )
                viewModel.onPermissionResult(PermissionKind.VpnConsent, PermissionResult.Granted)
                advanceUntilIdle()

                expectNoEvents()
                assertTrue(serviceController.startedModes.isEmpty())
                assertNull(viewModel.uiState.value.permissionSummary.issue)
                cancelAndIgnoreRemainingEvents()
            }

            collector.cancel()
        }

    @Test
    fun `pending action resumes across vpn consent while notifications are recommended`() =
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
    fun `local bypass toggle starts proxy mode independent of configured vpn mode`() =
        runTest {
            val serviceController = FakeServiceController()
            val viewModel =
                createViewModel(
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

            viewModel.onToggleLocalBypass(enabled = true)
            advanceUntilIdle()

            assertEquals(Mode.VPN, viewModel.uiState.value.configuredMode)
            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
            collector.cancel()
        }

    @Test
    fun `local bypass toggle stops active proxy session when disabled`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val viewModel =
                createViewModel(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                    initialize = false,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onToggleLocalBypass(enabled = false)
            advanceUntilIdle()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
            collector.cancel()
        }

    @Test
    fun `local bypass toggle stop policy follows visible active local card`() {
        val proxyState =
            MainUiState(
                appStatus = AppStatus.Running,
                activeMode = Mode.Proxy,
            )
        val vpnLocalState =
            MainUiState(
                appStatus = AppStatus.Running,
                activeMode = Mode.VPN,
                modeCards =
                    persistentListOf(
                        HomeModeCardUiState(mode = HomeMode.LocalDpiBypass, isActive = true),
                        HomeModeCardUiState(mode = HomeMode.RemoteVpn, isActive = false),
                        HomeModeCardUiState(mode = HomeMode.Diagnostic),
                    ),
            )

        assertTrue(shouldStopLocalBypassToggle(proxyState))
        assertTrue(shouldStopLocalBypassToggle(vpnLocalState))
    }

    @Test
    fun `vpn toggle starts vpn mode independent of configured proxy mode`() =
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
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onToggleVpn(enabled = true)
            advanceUntilIdle()

            assertEquals(Mode.Proxy, viewModel.uiState.value.configuredMode)
            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            collector.cancel()
        }

    @Test
    fun `vpn toggle stops active vpn session when disabled`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val viewModel =
                createViewModel(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                    initialize = false,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onToggleVpn(enabled = false)
            advanceUntilIdle()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
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
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val viewModel =
                createViewModel(
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = StubDiagnosticsHomeWorkflowService(),
                            compositeRunService = compositeRunService,
                        ),
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            advanceUntilIdle()

            assertEquals(listOf("home-run"), compositeRunService.startedRunIds)
            assertTrue(viewModel.homeDiagnosticsUiState.value.analysisAction.busy)
            collector.cancel()
        }

    @Test
    fun `actionable home audit enables verified vpn and shares archive for selected session`() =
        runTest {
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val shareService = StubDiagnosticsShareService()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                }
            val viewModel =
                createViewModel(
                    diagnosticsShareService = shareService,
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = homeWorkflowService,
                            compositeRunService = compositeRunService,
                        ),
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            compositeRunService.completeRun(
                outcome =
                    homeCompositeOutcome(
                        runId = "home-run",
                        fingerprintHash = "fp-1",
                        actionable = true,
                        recommendedSessionId = "audit-session",
                        bundleSessionIds = listOf("audit-session", "default-session", "dpi-session"),
                    ),
            )
            advanceUntilIdle()

            assertTrue(viewModel.homeDiagnosticsUiState.value.verifiedVpnAction.enabled)
            assertNull(viewModel.homeDiagnosticsUiState.value.remediationLadder)
            assertEquals(
                "home-run",
                viewModel.homeDiagnosticsUiState.value.analysisSheet
                    ?.runId,
            )

            viewModel.effects.test {
                viewModel.onShareHomeAnalysis()

                val effect = awaitItem() as MainEffect.ShareDiagnosticsArchive
                assertNull(shareService.archiveRequest?.requestedSessionId)
                assertEquals("home-run", shareService.archiveRequest?.homeRunId)
                assertEquals(
                    listOf("audit-session", "default-session", "dpi-session"),
                    shareService.archiveRequest?.sessionIds,
                )
                assertEquals(DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS, shareService.archiveRequest?.reason)
                assertEquals(shareService.archiveResult.absolutePath, effect.absolutePath)
                assertEquals(shareService.archiveResult.fileName, effect.fileName)
                cancelAndIgnoreRemainingEvents()
            }
            collector.cancel()
        }

    @Test
    fun `network change invalidates actionable home audit result`() =
        runTest {
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val serviceStateStore = FakeServiceStateStore()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                }
            val viewModel =
                createViewModel(
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = homeWorkflowService,
                            compositeRunService = compositeRunService,
                        ),
                    serviceStateStore = serviceStateStore,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            compositeRunService.completeRun(
                outcome =
                    homeCompositeOutcome(
                        runId = "home-run",
                        fingerprintHash = "fp-1",
                        actionable = true,
                        recommendedSessionId = "audit-session",
                    ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.homeDiagnosticsUiState.value.verifiedVpnAction.enabled)

            homeWorkflowService.currentFingerprint = "fp-2"
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertFalse(viewModel.homeDiagnosticsUiState.value.verifiedVpnAction.enabled)
            assertTrue(
                viewModel.homeDiagnosticsUiState.value.latestAudit
                    ?.stale == true,
            )
            assertEquals(
                DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS,
                viewModel.homeDiagnosticsUiState.value.remediationLadder
                    ?.primaryAction
                    ?.kind,
            )
            collector.cancel()
        }

    @Test
    fun `command line mode exposes home remediation ladder to advanced settings`() =
        runTest {
            val settings =
                AppSettings
                    .newBuilder()
                    .setOnboardingComplete(true)
                    .setRipdpiMode("vpn")
                    .setEnableCmdSettings(true)
                    .build()
            val viewModel =
                createViewModel(
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val ladder = viewModel.homeDiagnosticsUiState.value.remediationLadder
            assertNotNull(ladder)
            assertEquals(
                DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS,
                ladder?.primaryAction?.kind,
            )
            collector.cancel()
        }

    @Test
    fun `non actionable home audit exposes history remediation ladder`() =
        runTest {
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                }
            val viewModel =
                createViewModel(
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = homeWorkflowService,
                            compositeRunService = compositeRunService,
                        ),
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            compositeRunService.completeRun(
                outcome =
                    homeCompositeOutcome(
                        runId = "home-run",
                        fingerprintHash = "fp-1",
                        actionable = false,
                        recommendedSessionId = "audit-session",
                    ),
            )
            advanceUntilIdle()

            assertFalse(viewModel.homeDiagnosticsUiState.value.verifiedVpnAction.enabled)
            assertEquals(
                DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY,
                viewModel.homeDiagnosticsUiState.value.remediationLadder
                    ?.primaryAction
                    ?.kind,
            )
            assertEquals(
                DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY,
                viewModel.homeDiagnosticsUiState.value.analysisSheet
                    ?.remediationLadder
                    ?.primaryAction
                    ?.kind,
            )
            collector.cancel()
        }

    @Test
    fun `owned stack home remediation survives service restart on same network`() =
        runTest {
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val serviceStateStore = FakeServiceStateStore()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                }
            val viewModel =
                createViewModel(
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = homeWorkflowService,
                            compositeRunService = compositeRunService,
                        ),
                    serviceStateStore = serviceStateStore,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            compositeRunService.completeRun(
                outcome =
                    homeCompositeOutcome(
                        runId = "home-run",
                        fingerprintHash = "fp-1",
                        actionable = true,
                        recommendedSessionId = "audit-session",
                        directModeVerdict =
                            DirectModeVerdict(
                                result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                                reasonCode = DirectModeReasonCode.OWNED_STACK_REQUIRED,
                                authority = "example.org:443",
                            ),
                    ),
            )
            advanceUntilIdle()

            assertEquals(
                DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER,
                viewModel.homeDiagnosticsUiState.value.remediationLadder
                    ?.primaryAction
                    ?.kind,
            )

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            assertFalse(
                viewModel.homeDiagnosticsUiState.value.latestAudit
                    ?.stale == true,
            )
            assertEquals(
                DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER,
                viewModel.homeDiagnosticsUiState.value.remediationLadder
                    ?.primaryAction
                    ?.kind,
            )
            assertEquals(
                "https://example.org:443/",
                viewModel.homeDiagnosticsUiState.value.remediationLadder
                    ?.primaryAction
                    ?.targetUrl,
            )
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            collector.cancel()
        }

    @Test
    fun `verified vpn action starts vpn mode after actionable audit`() =
        runTest {
            val compositeRunService = StubDiagnosticsHomeCompositeRunService()
            val serviceController = FakeServiceController()
            val homeWorkflowService =
                StubDiagnosticsHomeWorkflowService().apply {
                    currentFingerprint = "fp-1"
                }
            val viewModel =
                createViewModel(
                    homeDiagnosticsServices =
                        HomeDiagnosticsServices(
                            workflowService = homeWorkflowService,
                            compositeRunService = compositeRunService,
                        ),
                    serviceController = serviceController,
                    permissionStatusProvider = grantedPermissionStatusProvider(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onRunHomeFullAnalysis()
            compositeRunService.completeRun(
                outcome =
                    homeCompositeOutcome(
                        runId = "home-run",
                        fingerprintHash = "fp-1",
                        actionable = true,
                        recommendedSessionId = "audit-session",
                    ),
            )
            advanceUntilIdle()

            viewModel.onStartVerifiedVpn()
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            collector.cancel()
        }

    @Test
    fun `applySavedStrategyConfig leaves halted service for next start`() =
        runTest {
            val serviceController = FakeServiceController()
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN),
                    initialize = false,
                )

            val result = viewModel.applySavedStrategyConfig()
            runCurrent()

            assertEquals(StrategyConfigApplyResult.NextSession, result)
            assertEquals(0, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
        }

    @Test
    fun `applySavedStrategyConfig restarts active service after halt`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val serviceController = FakeServiceController()
            val viewModel =
                createViewModel(
                    serviceController = serviceController,
                    serviceStateStore = serviceStateStore,
                    initialize = false,
                )

            val result = viewModel.applySavedStrategyConfig()
            runCurrent()

            assertEquals(StrategyConfigApplyResult.RestartingActiveService, result)
            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
            assertEquals(StrategyConfigApplyResult.RestartAlreadyPending, viewModel.applySavedStrategyConfig())

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
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
        hostPackCatalogUiStateStore: com.poyka.ripdpi.hosts.HostPackCatalogUiStateStore =
            com.poyka.ripdpi.hosts
                .HostPackCatalogUiStateStore(),
        strategyPackStateStore: com.poyka.ripdpi.data.InMemoryStrategyPackStateStore =
            com.poyka.ripdpi.data
                .InMemoryStrategyPackStateStore(),
        homeDiagnosticsServices: HomeDiagnosticsServices =
            HomeDiagnosticsServices(
                workflowService = StubDiagnosticsHomeWorkflowService(),
                compositeRunService = StubDiagnosticsHomeCompositeRunService(),
            ),
        initialize: Boolean = true,
    ): MainViewModel {
        val crashReportReader =
            com.poyka.ripdpi.diagnostics.crash.CrashReportReader(
                java.io.File(System.getProperty("java.io.tmpdir"), "ripdpi-test-crash-reports"),
            )
        return MainViewModel(
            appSettingsRepository = appSettingsRepository,
            mainServiceDependencies =
                MainServiceDependencies(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                    trafficStatsReader = FakeTrafficStatsReader(),
                    hardKillSwitchStateStore = FakeAndroidHardKillSwitchStateStore(),
                ),
            mainPermissionDependencies =
                MainPermissionDependencies(
                    permissionPlatformBridge = FakePermissionPlatformBridge(),
                    permissionStatusProvider = permissionStatusProvider,
                    permissionCoordinator = PermissionCoordinator(),
                ),
            mainDiagnosticsDependencies =
                MainDiagnosticsDependencies(
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = diagnosticsScanController,
                    diagnosticsShareService = diagnosticsShareService,
                    homeDiagnosticsServices = homeDiagnosticsServices,
                    latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
                ),
            mainControlPlaneDependencies =
                MainControlPlaneDependencies(
                    hostPackCatalogUiStateStore = hostPackCatalogUiStateStore,
                    hostPackCatalogUiStateCoordinator =
                        com.poyka.ripdpi.hosts.HostPackCatalogUiStateCoordinator(
                            repository =
                                object : com.poyka.ripdpi.hosts.HostPackCatalogRepository {
                                    override suspend fun loadSnapshot(): com.poyka.ripdpi.hosts
                                        .HostPackCatalogLoadResult =
                                        com.poyka.ripdpi.hosts.HostPackCatalogLoadResult(
                                            snapshot =
                                                com.poyka.ripdpi.data
                                                    .HostPackCatalogSnapshot(),
                                        )

                                    override suspend fun refreshSnapshot(): com.poyka.ripdpi.data
                                        .HostPackCatalogSnapshot =
                                        com.poyka.ripdpi.data
                                            .HostPackCatalogSnapshot()
                                },
                            clock =
                                com.poyka.ripdpi.hosts
                                    .HostPackCatalogClock { 0L },
                            stateStore = hostPackCatalogUiStateStore,
                        ),
                    strategyPackStateStore = strategyPackStateStore,
                ),
            mainLifecycleDependencies =
                MainLifecycleDependencies(
                    appLockLifecycleCoordinator =
                        MainAppLockLifecycleCoordinator(
                            com.poyka.ripdpi.security
                                .AppLockLifecycleObserver(RuntimeEnvironment.getApplication()),
                        ),
                    startupSideEffectsCoordinator =
                        MainStartupSideEffectsCoordinator(
                            appSettingsRepository = appSettingsRepository,
                            crashReportReader = crashReportReader,
                        ),
                    settingsDismissCoordinator =
                        MainSettingsDismissCoordinator(
                            appSettingsRepository = appSettingsRepository,
                        ),
                    crashReportCoordinator =
                        MainCrashReportCoordinator(
                            crashReportReader = crashReportReader,
                        ),
                ),
            stringResolver = FakeStringResolver(),
            activeTransportProvider = java.util.Optional.empty(),
        ).also { viewModel ->
            if (initialize) {
                viewModel.initialize()
            }
        }
    }

    private fun grantedPermissionStatusProvider(): FakePermissionStatusProvider =
        FakePermissionStatusProvider(
            snapshot =
                PermissionSnapshot(
                    vpnConsent = PermissionStatus.Granted,
                    alwaysOnVpn = PermissionStatus.Granted,
                    vpnLockdown = PermissionStatus.Granted,
                    notifications = PermissionStatus.Granted,
                    batteryOptimization = PermissionStatus.Granted,
                ),
        )

    @Suppress("UnusedPrivateMember")
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

    private fun homeCompositeOutcome(
        runId: String,
        fingerprintHash: String,
        actionable: Boolean,
        recommendedSessionId: String,
        bundleSessionIds: List<String> = listOf("audit-session"),
        directModeVerdict: DirectModeVerdict? = null,
    ): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = runId,
            fingerprintHash = fingerprintHash,
            actionable = actionable,
            headline = "Analysis complete and settings applied",
            summary = "Working TCP and QUIC winners found.",
            recommendationSummary = "TCP split + QUIC fake",
            confidenceSummary = "Confidence high",
            coverageSummary = "Coverage 92%",
            appliedSettings = listOf(DiagnosticsAppliedSetting("TCP/TLS lane", "Split")),
            recommendedSessionId = recommendedSessionId,
            directModeVerdict = directModeVerdict,
            stageSummaries =
                listOf(
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "automatic_audit",
                        stageLabel = "Automatic audit",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = recommendedSessionId,
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Audit complete",
                        summary = "Winning settings applied.",
                        recommendationContributor = actionable,
                    ),
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "default_connectivity",
                        stageLabel = "Default diagnostics",
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = bundleSessionIds.getOrNull(1),
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Default diagnostics complete",
                        summary = "Baseline connectivity checks passed.",
                    ),
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "dpi_full",
                        stageLabel = "DPI detector full",
                        profileId = "ru-dpi-full",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = bundleSessionIds.getOrNull(2),
                        status = DiagnosticsHomeCompositeStageStatus.FAILED,
                        headline = "DPI detector full partial failure",
                        summary = "Some extended checks were unavailable.",
                    ),
                ),
            completedStageCount = 2,
            failedStageCount = 1,
            skippedStageCount = 0,
            bundleSessionIds = bundleSessionIds,
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
