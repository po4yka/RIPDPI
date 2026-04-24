package com.poyka.ripdpi.activities

import android.os.SystemClock
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.collections.immutable.toImmutableList

private const val ConnectionActuatorStageCount = 5
private const val ConnectingStageDurationMs = 1_200L
private const val ConnectingStageProgressOffset = 0.4f
private const val FailedStageProgressOffset = 0.55f
private const val UndockedFaultMaxCarriageFraction = 0.92f

internal enum class MainPrimaryConnectionAction {
    NONE,
    START_CONFIGURED_MODE,
    STOP,
}

internal fun resolveEffectiveConnectionState(
    appStatus: AppStatus,
    runtimeConnectionState: ConnectionState,
): ConnectionState =
    when {
        appStatus == AppStatus.Halted && runtimeConnectionState == ConnectionState.Connected -> {
            ConnectionState.Disconnected
        }

        appStatus == AppStatus.Running && runtimeConnectionState == ConnectionState.Disconnected -> {
            ConnectionState.Connecting
        }

        else -> {
            runtimeConnectionState
        }
    }

internal fun resolvePrimaryConnectionAction(
    connectionState: ConnectionState,
    appStatus: AppStatus,
): MainPrimaryConnectionAction =
    when (connectionState) {
        ConnectionState.Connecting -> {
            MainPrimaryConnectionAction.NONE
        }

        ConnectionState.Connected -> {
            MainPrimaryConnectionAction.STOP
        }

        ConnectionState.Disconnected,
        ConnectionState.Error,
        -> {
            when (appStatus) {
                AppStatus.Halted -> MainPrimaryConnectionAction.START_CONFIGURED_MODE
                AppStatus.Running -> MainPrimaryConnectionAction.STOP
            }
        }
    }

internal fun buildMainUiState(
    inputs: MainUiInputs,
    homeDiagnostics: HomeDiagnosticsRuntimeState,
    stringResolver: StringResolver,
    approachSummary: HomeApproachSummaryUiState?,
): MainUiState {
    val settings = inputs.settings
    val (status, activeMode) = inputs.statusAndMode
    val runtime = inputs.runtime
    val permissions = inputs.permissions
    val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
    val permissionSummary =
        buildPermissionSummary(
            snapshot = permissions.snapshot,
            issue = permissions.issue,
            configuredMode = configuredMode,
            stringResolver = stringResolver,
            deviceManufacturer =
                android.os.Build.MANUFACTURER
                    .orEmpty(),
            batteryBannerDismissed = settings.batteryBannerDismissed,
            backgroundGuidanceDismissed = settings.backgroundGuidanceDismissed,
        )
    val effectiveConnectionState =
        resolveEffectiveConnectionState(
            appStatus = status,
            runtimeConnectionState = runtime.connectionState,
        )
    return MainUiState(
        appStatus = status,
        activeMode = activeMode,
        configuredMode = configuredMode,
        proxyIp = settings.proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080",
        theme = settings.appTheme.ifEmpty { "system" },
        connectionState = effectiveConnectionState,
        connectionActuator =
            buildConnectionActuatorUiState(
                settings = settings,
                activeMode = activeMode,
                configuredMode = configuredMode,
                connectionState = effectiveConnectionState,
                runtime = runtime,
                telemetry = inputs.telemetry,
                approachSummary = approachSummary,
                stringResolver = stringResolver,
            ),
        connectionDuration = runtime.connectionDuration,
        dataTransferred = runtime.dataTransferred,
        errorMessage = runtime.errorMessage,
        permissionSummary = permissionSummary,
        approachSummary = approachSummary,
        homeDiagnostics =
            buildHomeDiagnosticsUiState(
                settings = settings,
                appStatus = status,
                connectionState = effectiveConnectionState,
                runtime = homeDiagnostics,
                stringResolver = stringResolver,
            ),
        controlPlaneHealthSummary =
            stringResolver.buildControlPlaneHealthSummary(
                hostPackCatalog = inputs.hostPackCatalog,
                strategyPackRuntimeState = inputs.strategyPackRuntimeState,
            ),
    )
}

@Suppress("LongParameterList")
internal fun buildConnectionActuatorUiState(
    settings: AppSettings,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    runtime: ConnectionRuntimeState,
    telemetry: ServiceTelemetrySnapshot,
    approachSummary: HomeApproachSummaryUiState?,
    stringResolver: StringResolver,
): HomeConnectionActuatorUiState {
    val mode = if (connectionState == ConnectionState.Connected) activeMode else configuredMode
    val routeLabel = approachSummary?.title ?: routeLabelForMode(mode, settings, stringResolver)
    val warningStage =
        telemetryWarningStage(telemetry)
            .takeIf { connectionState == ConnectionState.Connected }
    val failedStage =
        telemetryFailureStage(telemetry)
            .takeIf { connectionState == ConnectionState.Error }
            ?: HomeConnectionActuatorStage.Tunnel.takeIf { connectionState == ConnectionState.Error }
    val activeStage =
        connectingStage(runtime.connectingStartedAtMs)
            .takeIf { connectionState == ConnectionState.Connecting }
    val status =
        when {
            connectionState == ConnectionState.Connected && warningStage != null -> {
                HomeConnectionActuatorStatus.Degraded
            }

            connectionState == ConnectionState.Connected -> {
                HomeConnectionActuatorStatus.Locked
            }

            connectionState == ConnectionState.Connecting -> {
                HomeConnectionActuatorStatus.Engaging
            }

            connectionState == ConnectionState.Error -> {
                HomeConnectionActuatorStatus.Fault
            }

            else -> {
                HomeConnectionActuatorStatus.Open
            }
        }

    return HomeConnectionActuatorUiState(
        status = status,
        leadingLabel = stringResolver.getString(R.string.home_connection_actuator_open),
        trailingLabel = stringResolver.getString(R.string.home_connection_actuator_secure),
        routeLabel = routeLabel,
        statusDescription =
            actuatorStatusDescription(
                status = status,
                warningStage = warningStage,
                failedStage = failedStage,
                stringResolver = stringResolver,
            ),
        actionLabel = actuatorActionLabel(status, stringResolver),
        carriageFraction =
            actuatorCarriageFraction(
                status = status,
                activeStage = activeStage,
                failedStage = failedStage,
            ),
        stages =
            HomeConnectionActuatorStage.entries
                .map { stage ->
                    HomeConnectionActuatorStageUiState(
                        stage = stage,
                        label = stage.label(stringResolver),
                        state =
                            stageState(
                                stage = stage,
                                status = status,
                                activeStage = activeStage,
                                warningStage = warningStage,
                                failedStage = failedStage,
                            ),
                    )
                }.toImmutableList(),
    )
}

private fun routeLabelForMode(
    mode: Mode,
    settings: AppSettings,
    stringResolver: StringResolver,
): String =
    when (mode) {
        Mode.VPN -> {
            stringResolver.getString(R.string.home_mode_vpn)
        }

        Mode.Proxy -> {
            val ip = settings.proxyIp.ifEmpty { "127.0.0.1" }
            val port = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080"
            "${stringResolver.getString(R.string.home_mode_proxy)} · $ip:$port"
        }
    }

private fun connectingStage(connectingStartedAtMs: Long?): HomeConnectionActuatorStage {
    val startedAt = connectingStartedAtMs ?: return HomeConnectionActuatorStage.Network
    val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    val stageIndex = (elapsed / ConnectingStageDurationMs).toInt().coerceIn(0, ConnectionActuatorStageCount - 1)
    return HomeConnectionActuatorStage.entries[stageIndex]
}

private fun actuatorCarriageFraction(
    status: HomeConnectionActuatorStatus,
    activeStage: HomeConnectionActuatorStage?,
    failedStage: HomeConnectionActuatorStage?,
): Float =
    when (status) {
        HomeConnectionActuatorStatus.Open -> {
            0f
        }

        HomeConnectionActuatorStatus.Locked,
        HomeConnectionActuatorStatus.Degraded,
        -> {
            1f
        }

        HomeConnectionActuatorStatus.Engaging -> {
            (((activeStage?.ordinal ?: 0) + ConnectingStageProgressOffset) / ConnectionActuatorStageCount)
                .coerceIn(0f, 1f)
        }

        HomeConnectionActuatorStatus.Fault -> {
            val failedProgress =
                ((failedStage?.ordinal ?: HomeConnectionActuatorStage.Tunnel.ordinal) + FailedStageProgressOffset) /
                    ConnectionActuatorStageCount
            failedProgress.coerceIn(0f, UndockedFaultMaxCarriageFraction)
        }
    }

private fun stageState(
    stage: HomeConnectionActuatorStage,
    status: HomeConnectionActuatorStatus,
    activeStage: HomeConnectionActuatorStage?,
    warningStage: HomeConnectionActuatorStage?,
    failedStage: HomeConnectionActuatorStage?,
): HomeConnectionActuatorStageState =
    when {
        status == HomeConnectionActuatorStatus.Open -> {
            HomeConnectionActuatorStageState.Pending
        }

        status == HomeConnectionActuatorStatus.Engaging && stage == activeStage -> {
            HomeConnectionActuatorStageState.Active
        }

        status == HomeConnectionActuatorStatus.Engaging &&
            activeStage != null &&
            stage.ordinal < activeStage.ordinal -> {
            HomeConnectionActuatorStageState.Complete
        }

        status == HomeConnectionActuatorStatus.Fault && stage == failedStage -> {
            HomeConnectionActuatorStageState.Failed
        }

        status == HomeConnectionActuatorStatus.Fault && failedStage != null && stage.ordinal < failedStage.ordinal -> {
            HomeConnectionActuatorStageState.Complete
        }

        status == HomeConnectionActuatorStatus.Degraded && stage == warningStage -> {
            HomeConnectionActuatorStageState.Warning
        }

        status == HomeConnectionActuatorStatus.Locked || status == HomeConnectionActuatorStatus.Degraded -> {
            HomeConnectionActuatorStageState.Complete
        }

        else -> {
            HomeConnectionActuatorStageState.Pending
        }
    }

private fun actuatorStatusDescription(
    status: HomeConnectionActuatorStatus,
    warningStage: HomeConnectionActuatorStage?,
    failedStage: HomeConnectionActuatorStage?,
    stringResolver: StringResolver,
): String =
    when (status) {
        HomeConnectionActuatorStatus.Open -> {
            stringResolver.getString(R.string.home_connection_actuator_state_open)
        }

        HomeConnectionActuatorStatus.Engaging -> {
            stringResolver.getString(R.string.home_connection_actuator_state_engaging)
        }

        HomeConnectionActuatorStatus.Locked -> {
            stringResolver.getString(R.string.home_connection_actuator_state_locked)
        }

        HomeConnectionActuatorStatus.Degraded -> {
            stringResolver.getString(
                R.string.home_connection_actuator_state_degraded,
                warningStage?.label(stringResolver).orEmpty(),
            )
        }

        HomeConnectionActuatorStatus.Fault -> {
            stringResolver.getString(
                R.string.home_connection_actuator_state_fault,
                failedStage?.label(stringResolver).orEmpty(),
            )
        }
    }

private fun actuatorActionLabel(
    status: HomeConnectionActuatorStatus,
    stringResolver: StringResolver,
): String =
    when (status) {
        HomeConnectionActuatorStatus.Open,
        HomeConnectionActuatorStatus.Fault,
        -> stringResolver.getString(R.string.home_connection_actuator_action_activate)

        HomeConnectionActuatorStatus.Locked,
        HomeConnectionActuatorStatus.Degraded,
        -> stringResolver.getString(R.string.home_connection_actuator_action_deactivate)

        HomeConnectionActuatorStatus.Engaging -> stringResolver.getString(R.string.home_connection_actuator_action_wait)
    }

private fun HomeConnectionActuatorStage.label(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            HomeConnectionActuatorStage.Network -> R.string.home_connection_stage_network
            HomeConnectionActuatorStage.Dns -> R.string.home_connection_stage_dns
            HomeConnectionActuatorStage.Handshake -> R.string.home_connection_stage_handshake
            HomeConnectionActuatorStage.Tunnel -> R.string.home_connection_stage_tunnel
            HomeConnectionActuatorStage.Route -> R.string.home_connection_stage_route
        },
    )

private fun telemetryWarningStage(telemetry: ServiceTelemetrySnapshot): HomeConnectionActuatorStage? =
    when {
        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.NetworkHandover ||
            telemetry.networkHandoverState != null -> {
            HomeConnectionActuatorStage.Network
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.DnsInterference ||
            telemetry.tunnelTelemetry.resolverFallbackActive ||
            telemetry.tunnelTelemetry.dnsFailuresTotal > 0 -> {
            HomeConnectionActuatorStage.Dns
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.TlsInterference ||
            !telemetry.proxyTelemetry.lastHandshakeError.isNullOrBlank() -> {
            HomeConnectionActuatorStage.Handshake
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.TunnelEstablish ||
            telemetry.tunnelTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
            telemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount > 0 -> {
            HomeConnectionActuatorStage.Tunnel
        }

        telemetry.proxyTelemetry.routeChanges > 0 ||
            telemetry.runtimeFieldTelemetry.proxyRouteRetryCount > 0 ||
            !telemetry.proxyTelemetry.lastFallbackAction.isNullOrBlank() -> {
            HomeConnectionActuatorStage.Route
        }

        else -> {
            null
        }
    }

private fun telemetryFailureStage(telemetry: ServiceTelemetrySnapshot): HomeConnectionActuatorStage? =
    when (telemetry.runtimeFieldTelemetry.failureClass) {
        FailureClass.NetworkHandover -> {
            HomeConnectionActuatorStage.Network
        }

        FailureClass.DnsInterference -> {
            HomeConnectionActuatorStage.Dns
        }

        FailureClass.TlsInterference,
        FailureClass.FingerprintPolicy,
        -> {
            HomeConnectionActuatorStage.Handshake
        }

        FailureClass.TunnelEstablish,
        FailureClass.WarpProvisioning,
        FailureClass.WarpEndpoint,
        -> {
            HomeConnectionActuatorStage.Tunnel
        }

        FailureClass.Timeout,
        FailureClass.ResetAbort,
        FailureClass.NativeIo,
        FailureClass.Unexpected,
        null,
        -> {
            null
        }
    }
