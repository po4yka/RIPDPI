package com.poyka.ripdpi.activities

import android.os.SystemClock
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
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

        // Boot/LMK resume: surface the bring-up as Connecting so the actuator
        // animates "engaging" instead of flashing Halted during the restart window.
        appStatus == AppStatus.Reconnecting && runtimeConnectionState != ConnectionState.Connected -> {
            ConnectionState.Connecting
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
            MainPrimaryConnectionAction.STOP
        }

        ConnectionState.Connected -> {
            MainPrimaryConnectionAction.STOP
        }

        ConnectionState.Disconnected,
        ConnectionState.Error,
        -> {
            when (appStatus) {
                AppStatus.Halted -> MainPrimaryConnectionAction.START_CONFIGURED_MODE

                // Grouped with Running for exhaustiveness / as a defensive default.
                // Not reached while appStatus == Reconnecting in practice:
                // resolveEffectiveConnectionState maps Reconnecting to Connecting
                // (-> STOP) above, so the same actuator cancels both startup and
                // transport failover.
                AppStatus.Reconnecting,
                AppStatus.Running,
                -> MainPrimaryConnectionAction.STOP
            }
        }
    }

internal fun buildMainUiState(
    inputs: MainUiInputs,
    stringResolver: StringResolver,
    approachSummary: HomeApproachSummaryUiState?,
): MainUiState {
    val settings = inputs.settings
    val (status, activeMode) = inputs.statusAndMode
    val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
    val permissionSummary = buildHomePermissionSummary(inputs.permissions, settings, configuredMode, stringResolver)
    val effectiveConnectionState =
        resolveEffectiveConnectionState(
            appStatus = status,
            runtimeConnectionState = inputs.runtime.connectionState,
        )
    val hardKillSwitch =
        buildHardKillSwitchUiState(
            snapshot = inputs.hardKillSwitch,
            configuredMode = configuredMode,
            activeMode = activeMode,
            appStatus = status,
            stringResolver = stringResolver,
        )
    val homeDiagnostics = HomeDiagnosticsUiState()
    val vpnDataPlaneStatus =
        resolveVpnDataPlaneStatus(
            appStatus = status,
            activeMode = activeMode,
            evidence = inputs.pathValidation,
        )
    val connectionActuator =
        buildConnectionActuatorUiState(
            settings = settings,
            activeMode = activeMode,
            configuredMode = configuredMode,
            connectionState = effectiveConnectionState,
            vpnDataPlaneStatus = vpnDataPlaneStatus,
            runtime = inputs.runtime,
            telemetry = inputs.telemetry,
            approachSummary = approachSummary,
            hardKillSwitch = hardKillSwitch,
            stringResolver = stringResolver,
        )
    val modeCards =
        buildHomeModeCards(
            HomeModeCardsInput(
                settings = settings,
                activeMode = activeMode,
                configuredMode = configuredMode,
                connectionState = effectiveConnectionState,
                vpnDataPlaneStatus = vpnDataPlaneStatus,
                connectionDuration = inputs.runtime.connectionDuration,
                homeDiagnostics = homeDiagnostics,
                stringResolver = stringResolver,
            ),
        )
    return MainUiState(
        settingsLoaded = true,
        appStatus = status,
        activeMode = activeMode,
        configuredMode = configuredMode,
        proxyIp = settings.proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080",
        theme = settings.appTheme.ifEmpty { "system" },
        connectionState = effectiveConnectionState,
        connectionActuator = connectionActuator,
        connectionDuration = inputs.runtime.connectionDuration,
        dataTransferred = inputs.runtime.dataTransferred,
        errorMessage = inputs.runtime.errorMessage,
        permissionSummary = permissionSummary,
        hardKillSwitch = hardKillSwitch,
        approachSummary = approachSummary,
        homeDiagnostics = homeDiagnostics,
        modeCards = modeCards,
        controlPlaneHealthSummary =
            stringResolver.buildControlPlaneHealthSummary(
                hostPackCatalog = inputs.hostPackCatalog,
                strategyPackRuntimeState = inputs.strategyPackRuntimeState,
            ),
        connectionQuality = resolveConnectionQuality(inputs.telemetry.tunnelTelemetry),
        networkCondition = resolveMainNetworkCondition(status, inputs.pathValidation),
        xrayProviderSnapshot = inputs.telemetry.xrayProviderSnapshot,
    )
}

private fun buildHomePermissionSummary(
    permissions: PermissionRuntimeState,
    settings: AppSettings,
    configuredMode: Mode,
    stringResolver: StringResolver,
): PermissionSummaryUiState =
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

@Suppress("LongParameterList")
internal fun buildConnectionActuatorUiState(
    settings: AppSettings,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    vpnDataPlaneStatus: VpnDataPlaneStatus,
    runtime: ConnectionRuntimeState,
    telemetry: ServiceTelemetrySnapshot,
    approachSummary: HomeApproachSummaryUiState?,
    hardKillSwitch: HardKillSwitchUiState = HardKillSwitchUiState(),
    stringResolver: StringResolver,
): HomeConnectionActuatorUiState {
    val mode = if (connectionState == ConnectionState.Connected) activeMode else configuredMode
    val routeLabel = approachSummary?.title ?: routeLabelForMode(mode, settings, stringResolver)
    val egressBacked = isForeignExitLive(settings, telemetry)
    val vpnDataPlaneWarning = vpnDataPlaneWarning(connectionState, vpnDataPlaneStatus)
    val warningStage = actuatorWarningStage(connectionState, vpnDataPlaneWarning, telemetry)
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
        trailingLabel = actuatorTrailingLabel(egressBacked, stringResolver),
        routeLabel = routeLabel,
        statusDescription =
            actuatorStatusDescription(
                status = status,
                egressBacked = egressBacked,
                vpnDataPlaneStatus = vpnDataPlaneWarning,
                warningStage = warningStage,
                failedStage = failedStage,
                stringResolver = stringResolver,
            ).let { description ->
                if (hardKillSwitch.visible) {
                    "$description. ${hardKillSwitch.label}. ${hardKillSwitch.summary}"
                } else {
                    description
                }
            },
        actionLabel =
            hardKillSwitch.actionLabel.takeIf { hardKillSwitch.blocksDisconnect }
                ?: actuatorActionLabel(status, stringResolver),
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
        deactivationEnabled = false.takeIf { hardKillSwitch.blocksDisconnect },
    )
}

private fun vpnDataPlaneWarning(
    connectionState: ConnectionState,
    status: VpnDataPlaneStatus,
): VpnDataPlaneStatus? =
    status.takeIf {
        connectionState == ConnectionState.Connected &&
            it != VpnDataPlaneStatus.Working &&
            it != VpnDataPlaneStatus.NotApplicable
    }

private fun actuatorWarningStage(
    connectionState: ConnectionState,
    vpnDataPlaneWarning: VpnDataPlaneStatus?,
    telemetry: ServiceTelemetrySnapshot,
): HomeConnectionActuatorStage? =
    (
        HomeConnectionActuatorStage.Route.takeIf { vpnDataPlaneWarning != null }
            ?: telemetryWarningStage(telemetry)
    ).takeIf { connectionState == ConnectionState.Connected }

private fun routeLabelForMode(
    mode: Mode,
    settings: AppSettings,
    stringResolver: StringResolver,
): String =
    when (mode) {
        Mode.VPN -> {
            actuatorLabelForRemoteVpn(settings, stringResolver)
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
    egressBacked: Boolean,
    vpnDataPlaneStatus: VpnDataPlaneStatus?,
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
            stringResolver.getString(
                if (egressBacked) {
                    R.string.home_connection_actuator_state_locked
                } else {
                    R.string.home_connection_actuator_state_direct
                },
            )
        }

        HomeConnectionActuatorStatus.Degraded -> {
            when (vpnDataPlaneStatus) {
                VpnDataPlaneStatus.Checking -> {
                    stringResolver.getString(R.string.home_connection_actuator_state_vpn_checking)
                }

                VpnDataPlaneStatus.Unverified -> {
                    stringResolver.getString(R.string.home_connection_actuator_state_vpn_unverified)
                }

                VpnDataPlaneStatus.Unavailable -> {
                    stringResolver.getString(R.string.home_connection_actuator_state_vpn_unavailable)
                }

                VpnDataPlaneStatus.NotApplicable,
                VpnDataPlaneStatus.Working,
                null,
                -> {
                    stringResolver.getString(
                        if (egressBacked) {
                            R.string.home_connection_actuator_state_degraded
                        } else {
                            R.string.home_connection_actuator_state_direct_degraded
                        },
                        warningStage?.label(stringResolver).orEmpty(),
                    )
                }
            }
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
        HomeConnectionActuatorStatus.Engaging,
        -> stringResolver.getString(R.string.home_connection_actuator_action_deactivate)
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
        // Foreign exit lost after connecting (audit P1-1): the base stays up but traffic now
        // egresses direct, so surface a Route-stage warning that flips Locked -> Degraded.
        // The egress-aware predicate lives in isForeignExitLost (ActuatorEgressHonesty) so
        // this aggregator stays free of that feature family.
        isForeignExitLost(telemetry) -> {
            HomeConnectionActuatorStage.Route
        }

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
