package com.poyka.ripdpi.automation

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.MainActivityHostCommand
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceAutomationController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private val AutomationIntentAction = "${BuildConfig.APPLICATION_ID}.automation.ACTION"
private val AutomationIntentKindExtra = "${BuildConfig.APPLICATION_ID}.automation.KIND"

private enum class AutomationIntentKind {
    VpnConsent,
    AppSettings,
    BatteryOptimization,
}

private data class AutomationRuntimeState(
    val config: AutomationLaunchConfig = AutomationLaunchConfig(),
    val permissionSnapshot: PermissionSnapshot = PermissionSnapshot(),
)

@Singleton
class DebugAutomationController
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val serviceStateStore: ServiceStateStore,
    ) : AutomationController,
        ServiceAutomationController {
        private val state = AtomicReference(AutomationRuntimeState())

        override fun prepareLaunch(intent: Intent?): AutomationLaunchConfig {
            val previousState = state.get()
            val config =
                resolveAutomationLaunchConfig(
                    instrumentationArgs = instrumentationArgs(),
                    intentArgs = intentArgs(intent),
                )

            val nextState =
                if (config.enabled) {
                    AutomationRuntimeState(
                        config = config,
                        permissionSnapshot = permissionSnapshotFor(config.permissionPreset),
                    )
                } else {
                    AutomationRuntimeState()
                }
            state.set(nextState)

            System.setProperty("ripdpi.staticMotion", config.disableMotion.toString())

            runBlocking {
                if (config.enabled) {
                    if (config.resetState) {
                        appSettingsRepository.replace(AppSettingsSerializer.defaultValue)
                    }

                    val seededSettings = buildSeedSettings(config)
                    appSettingsRepository.replace(seededSettings)
                    applyServicePreset(
                        preset = config.servicePreset,
                        configuredMode = Mode.fromString(seededSettings.ripdpiMode),
                    )
                } else if (
                    previousState.config.enabled &&
                    previousState.config.servicePreset != AutomationServicePreset.Live
                ) {
                    applyIdleServiceState(serviceStateStore.status.value.second)
                }
            }

            return config
        }

        override fun currentLaunchConfig(): AutomationLaunchConfig = state.get().config

        override fun currentPermissionSnapshot(defaultSnapshot: PermissionSnapshot): PermissionSnapshot =
            state.get().takeIf { it.config.enabled }?.permissionSnapshot ?: defaultSnapshot

        override fun prepareVpnPermissionIntent(defaultIntent: Intent?): Intent? {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return defaultIntent
            }

            return if (currentState.permissionSnapshot.vpnConsent == PermissionStatus.Granted) {
                null
            } else {
                automationIntent(AutomationIntentKind.VpnConsent)
            }
        }

        override fun createAppSettingsIntent(defaultIntent: Intent): Intent {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return defaultIntent
            }

            return automationIntent(AutomationIntentKind.AppSettings)
        }

        override fun createBatteryOptimizationIntent(defaultIntent: Intent): Intent {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return defaultIntent
            }

            return automationIntent(AutomationIntentKind.BatteryOptimization)
        }

        override fun interceptHostCommand(
            command: Any,
            viewModel: MainViewModel,
        ): Boolean {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return false
            }

            return when (command) {
                MainActivityHostCommand.RequestNotificationsPermission -> {
                    grantPermission(
                        kind = PermissionKind.Notifications,
                        viewModel = viewModel,
                        result = PermissionResult.Granted,
                    )
                    true
                }

                is MainActivityHostCommand.RequestVpnConsent -> {
                    grantPermission(
                        kind = PermissionKind.VpnConsent,
                        viewModel = viewModel,
                        result = PermissionResult.Granted,
                    )
                    true
                }

                is MainActivityHostCommand.RequestBatteryOptimization -> {
                    grantPermission(
                        kind = PermissionKind.BatteryOptimization,
                        viewModel = viewModel,
                        result = PermissionResult.ReturnedFromSettings,
                    )
                    true
                }

                is MainActivityHostCommand.OpenIntent -> {
                    val permissionSnapshot = currentState.permissionSnapshot
                    when {
                        permissionSnapshot.notifications != PermissionStatus.Granted -> {
                            grantPermission(
                                kind = PermissionKind.Notifications,
                                viewModel = viewModel,
                                result = PermissionResult.ReturnedFromSettings,
                            )
                            true
                        }

                        permissionSnapshot.batteryOptimization != PermissionStatus.Granted &&
                            permissionSnapshot.batteryOptimization != PermissionStatus.NotApplicable
                        -> {
                            grantPermission(
                                kind = PermissionKind.BatteryOptimization,
                                viewModel = viewModel,
                                result = PermissionResult.ReturnedFromSettings,
                            )
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }

                else -> {
                    false
                }
            }
        }

        override fun interceptStart(mode: Mode): Boolean {
            val config = state.get().config
            if (!config.enabled || config.servicePreset == AutomationServicePreset.Live) {
                return false
            }

            applyRunningServiceState(mode)
            return true
        }

        override fun interceptStop(currentMode: Mode): Boolean {
            val config = state.get().config
            if (!config.enabled || config.servicePreset == AutomationServicePreset.Live) {
                return false
            }

            applyIdleServiceState(currentMode)
            return true
        }

        private fun buildSeedSettings(config: AutomationLaunchConfig): AppSettings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setOnboardingComplete(true)
                .setBiometricEnabled(false)
                .setBackupPin("")
                .apply {
                    when (config.dataPreset) {
                        AutomationDataPreset.CleanHome -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setWebrtcProtectionEnabled(false)
                        }

                        AutomationDataPreset.SettingsReady -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setWebrtcProtectionEnabled(true)
                            setDnsProviderId("cloudflare")
                        }

                        AutomationDataPreset.DiagnosticsDemo -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setDiagnosticsMonitorEnabled(true)
                            setDiagnosticsActiveProfileId("default")
                            setNetworkStrategyMemoryEnabled(true)
                        }
                    }
                }.build()

        @Suppress("DEPRECATION")
        private fun instrumentationArgs(): Map<String, Any?> =
            runCatching {
                InstrumentationRegistry
                    .getArguments()
                    .keySet()
                    .associateWith { key -> InstrumentationRegistry.getArguments().get(key) }
            }.getOrDefault(emptyMap())

        @Suppress("DEPRECATION")
        private fun intentArgs(intent: Intent?): Map<String, Any?> =
            intent
                ?.let { source ->
                    buildMap {
                        if (source.hasExtra(AutomationLaunchContract.Enabled)) {
                            put(
                                AutomationLaunchContract.Enabled,
                                source.getBooleanExtra(AutomationLaunchContract.Enabled, false),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.ResetState)) {
                            put(
                                AutomationLaunchContract.ResetState,
                                source.getBooleanExtra(AutomationLaunchContract.ResetState, false),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.StartRoute)) {
                            put(
                                AutomationLaunchContract.StartRoute,
                                source.getStringExtra(AutomationLaunchContract.StartRoute),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.DisableMotion)) {
                            put(
                                AutomationLaunchContract.DisableMotion,
                                source.getBooleanExtra(AutomationLaunchContract.DisableMotion, false),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.PermissionPreset)) {
                            put(
                                AutomationLaunchContract.PermissionPreset,
                                source.getStringExtra(AutomationLaunchContract.PermissionPreset),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.ServicePreset)) {
                            put(
                                AutomationLaunchContract.ServicePreset,
                                source.getStringExtra(AutomationLaunchContract.ServicePreset),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.DataPreset)) {
                            put(
                                AutomationLaunchContract.DataPreset,
                                source.getStringExtra(AutomationLaunchContract.DataPreset),
                            )
                        }
                    }
                }.orEmpty()

        private fun permissionSnapshotFor(preset: AutomationPermissionPreset): PermissionSnapshot =
            when (preset) {
                AutomationPermissionPreset.Granted -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.Granted,
                    )
                }

                AutomationPermissionPreset.NotificationsMissing -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        notifications = PermissionStatus.RequiresSystemPrompt,
                        batteryOptimization = PermissionStatus.Granted,
                    )
                }

                AutomationPermissionPreset.VpnMissing -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.Granted,
                    )
                }

                AutomationPermissionPreset.BatteryReview -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    )
                }
            }

        private fun grantPermission(
            kind: PermissionKind,
            viewModel: MainViewModel,
            result: PermissionResult,
        ) {
            state.updateAndGet { current ->
                current.copy(
                    permissionSnapshot =
                        when (kind) {
                            PermissionKind.Notifications -> {
                                current.permissionSnapshot.copy(
                                    notifications = PermissionStatus.Granted,
                                )
                            }

                            PermissionKind.VpnConsent -> {
                                current.permissionSnapshot.copy(
                                    vpnConsent = PermissionStatus.Granted,
                                )
                            }

                            PermissionKind.BatteryOptimization -> {
                                current.permissionSnapshot.copy(
                                    batteryOptimization = PermissionStatus.Granted,
                                )
                            }
                        },
                )
            }
            viewModel.onPermissionResult(kind = kind, result = result)
        }

        private fun applyServicePreset(
            preset: AutomationServicePreset,
            configuredMode: Mode,
        ) {
            when (preset) {
                AutomationServicePreset.Idle,
                AutomationServicePreset.Live,
                -> {
                    applyIdleServiceState(configuredMode)
                }

                AutomationServicePreset.ConnectedProxy -> {
                    applyRunningServiceState(Mode.Proxy)
                }

                AutomationServicePreset.ConnectedVpn -> {
                    applyRunningServiceState(Mode.VPN)
                }
            }
        }

        private fun applyIdleServiceState(mode: Mode) {
            serviceStateStore.setStatus(AppStatus.Halted, mode)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = mode,
                    status = AppStatus.Halted,
                    proxyTelemetry = NativeRuntimeSnapshot.idle(source = "proxy"),
                    tunnelTelemetry = NativeRuntimeSnapshot.idle(source = "tunnel"),
                ),
            )
        }

        private fun applyRunningServiceState(mode: Mode) {
            val now = System.currentTimeMillis()
            val proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    activeSessions = 1,
                    totalSessions = 4,
                    listenerAddress = "127.0.0.1:1080",
                    upstreamAddress = "demo-gateway",
                    lastHost = "example.com",
                    capturedAt = now,
                )
            val tunnelTelemetry =
                if (mode == Mode.VPN) {
                    NativeRuntimeSnapshot(
                        source = "tunnel",
                        state = "running",
                        health = "healthy",
                        activeSessions = 1,
                        totalSessions = 2,
                        resolverId = "cloudflare",
                        resolverProtocol = "doh",
                        resolverEndpoint = "cloudflare-dns.com:443",
                        resolverLatencyMs = 24,
                        tunnelStats =
                            TunnelStats(
                                txPackets = 18,
                                txBytes = 6_912,
                                rxPackets = 21,
                                rxBytes = 8_448,
                            ),
                        capturedAt = now,
                    )
                } else {
                    NativeRuntimeSnapshot.idle(source = "tunnel")
                }

            serviceStateStore.setStatus(AppStatus.Running, mode)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = mode,
                    status = AppStatus.Running,
                    proxyTelemetry = proxyTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    tunnelStats = tunnelTelemetry.tunnelStats,
                    serviceStartedAt = now,
                    updatedAt = now,
                ),
            )
        }

        private fun automationIntent(kind: AutomationIntentKind): Intent =
            Intent(AutomationIntentAction)
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra(AutomationIntentKindExtra, kind.name)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugAutomationControllerModule {
    @Binds
    @Singleton
    abstract fun bindAutomationController(controller: DebugAutomationController): AutomationController

    @Binds
    @Singleton
    abstract fun bindServiceAutomationController(controller: DebugAutomationController): ServiceAutomationController
}
