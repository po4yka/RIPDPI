package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.service.telemetry.measurementSource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

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
    val runtime = inputs.runtime
    val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
    val permissionSummary =
        buildHomePermissionSummary(
            permissions = inputs.permissions,
            settings = settings,
            configuredMode = configuredMode,
            stringResolver = stringResolver,
        )
    val effectiveConnectionState =
        resolveEffectiveConnectionState(
            appStatus = status,
            runtimeConnectionState = runtime.connectionState,
        )
    val hardKillSwitch =
        buildHardKillSwitchUiState(
            snapshot = inputs.hardKillSwitch,
            configuredMode = configuredMode,
            activeMode = activeMode,
            appStatus = status,
            stringResolver = stringResolver,
        )
    val homeDiagnosticsUiState = HomeDiagnosticsUiState()
    val vpnDataPlaneStatus = inputs.resolveDataPlaneStatus()
    val connectionActuator =
        buildMainConnectionActuator(
            inputs = inputs,
            configuredMode = configuredMode,
            connectionState = effectiveConnectionState,
            vpnDataPlaneStatus = vpnDataPlaneStatus,
            approachSummary = approachSummary,
            hardKillSwitch = hardKillSwitch,
            stringResolver = stringResolver,
        )
    val modeCards =
        buildMainModeCards(
            inputs = inputs,
            configuredMode = configuredMode,
            connectionState = effectiveConnectionState,
            homeDiagnostics = homeDiagnosticsUiState,
            stringResolver = stringResolver,
        )
    val qualityRuntime = resolveConnectionQuality(inputs.telemetry)
    return MainUiState(
        settingsLoaded = true,
        appliedConfiguration = buildAppliedConfigurationUiState(inputs, stringResolver),
        appStatus = status,
        activeMode = activeMode,
        configuredMode = configuredMode,
        proxyIp = settings.proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080",
        theme = settings.appTheme.ifEmpty { "system" },
        connectionState = effectiveConnectionState,
        connectionActuator = connectionActuator,
        connectionDuration = runtime.connectionDuration,
        dataTransferred = runtime.dataTransferred,
        errorMessage = runtime.errorMessage,
        permissionSummary = permissionSummary,
        hardKillSwitch = hardKillSwitch,
        approachSummary = approachSummary,
        homeDiagnostics = homeDiagnosticsUiState,
        modeCards = modeCards,
        controlPlaneHealthSummary =
            stringResolver.buildControlPlaneHealthSummary(
                hostPackCatalog = inputs.hostPackCatalog,
                strategyPackRuntimeState = inputs.strategyPackRuntimeState,
            ),
        connectionQuality = qualityRuntime?.connectionQuality,
        connectionQualitySource = qualityRuntime?.measurementSource(inputs.telemetry),
        networkCondition = resolveMainNetworkCondition(status, inputs.pathValidation),
        xrayProviderSnapshot = inputs.telemetry.xrayProviderSnapshot,
    )
}

private fun buildMainModeCards(
    inputs: MainUiInputs,
    configuredMode: Mode,
    connectionState: ConnectionState,
    homeDiagnostics: HomeDiagnosticsUiState,
    stringResolver: StringResolver,
): ImmutableList<HomeModeCardUiState> =
    buildHomeModeCards(
        HomeModeCardsInput(
            settings = inputs.settings,
            activeMode = inputs.statusAndMode.second,
            configuredMode = configuredMode,
            connectionState = connectionState,
            vpnDataPlaneStatus = inputs.resolveDataPlaneStatus(),
            connectionDuration = inputs.runtime.connectionDuration,
            homeDiagnostics = homeDiagnostics,
            stringResolver = stringResolver,
        ),
    ).map { card ->
        if (!card.isActive || card.mode == HomeMode.Diagnostic) {
            card
        } else {
            val applied = buildAppliedConfigurationUiState(inputs, stringResolver)
            card.copy(
                primaryLabel = applied.status,
                summaryFacets =
                    listOfNotNull(
                        applied.confirmedSummary?.let {
                            HomeModeSummaryFacet(
                                label =
                                    stringResolver.getString(
                                        if (applied.previous) {
                                            R.string.runtime_config_last_confirmed
                                        } else {
                                            R.string.runtime_config_applied
                                        },
                                    ),
                                value = it,
                            )
                        },
                    ).toImmutableList(),
            )
        }
    }.toImmutableList()

private fun MainUiInputs.resolveDataPlaneStatus(): VpnDataPlaneStatus =
    resolveVpnDataPlaneStatus(statusAndMode.first, statusAndMode.second, pathValidation)

private fun buildMainConnectionActuator(
    inputs: MainUiInputs,
    configuredMode: Mode,
    connectionState: ConnectionState,
    vpnDataPlaneStatus: VpnDataPlaneStatus,
    approachSummary: HomeApproachSummaryUiState?,
    hardKillSwitch: HardKillSwitchUiState,
    stringResolver: StringResolver,
): HomeConnectionActuatorUiState =
    buildConnectionActuatorUiState(
        settings = inputs.settings,
        activeMode = inputs.statusAndMode.second,
        configuredMode = configuredMode,
        connectionState = connectionState,
        vpnDataPlaneStatus = vpnDataPlaneStatus,
        vpnValidationWarning =
            vpnValidationWarning(
                appStatus = inputs.statusAndMode.first,
                activeMode = inputs.statusAndMode.second,
                evidence = inputs.pathValidation,
            ),
        vpnForwardingWarning =
            vpnForwardingWarning(
                appStatus = inputs.statusAndMode.first,
                activeMode = inputs.statusAndMode.second,
                evidence = inputs.pathValidation,
            ),
        runtime = inputs.runtime,
        appStatus = inputs.statusAndMode.first,
        telemetry = inputs.telemetry,
        approachSummary = approachSummary,
        hardKillSwitch = hardKillSwitch,
        stringResolver = stringResolver,
    )

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

/**
 * The failure's own words, carried by the actuator rather than by a banner
 * beside it. Empty outside a fault, so the control has nothing to render.
 */
private fun actuatorFaultDetail(
    status: HomeConnectionActuatorStatus,
    runtime: ConnectionRuntimeState,
): String =
    runtime.errorMessage
        .orEmpty()
        .takeIf { status == HomeConnectionActuatorStatus.Fault }
        .orEmpty()

@Suppress("LongParameterList")
internal fun buildConnectionActuatorUiState(
    settings: AppSettings,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    vpnDataPlaneStatus: VpnDataPlaneStatus = VpnDataPlaneStatus.NotApplicable,
    vpnValidationWarning: Boolean = false,
    vpnForwardingWarning: Boolean = false,
    runtime: ConnectionRuntimeState,
    telemetry: ServiceTelemetrySnapshot,
    approachSummary: HomeApproachSummaryUiState?,
    appStatus: AppStatus? = null,
    hardKillSwitch: HardKillSwitchUiState = HardKillSwitchUiState(),
    stringResolver: StringResolver,
): HomeConnectionActuatorUiState {
    val mode = if (connectionState == ConnectionState.Connected) activeMode else configuredMode
    val egressBacked = isForeignExitLive(settings, telemetry)
    val vpnWarning = vpnDataPlaneWarning(connectionState, vpnDataPlaneStatus)
    val warningStage =
        (
            HomeConnectionActuatorStage.Route.takeIf { vpnWarning != null }
                ?: HomeConnectionActuatorStage.Network.takeIf { vpnValidationWarning }
                ?: HomeConnectionActuatorStage.Tunnel.takeIf { vpnForwardingWarning }
                ?: telemetryWarningStage(telemetry)
        ).takeIf { connectionState == ConnectionState.Connected }
    val failedStage =
        telemetryFailureStage(telemetry)
            .takeIf { connectionState == ConnectionState.Error }
            ?: HomeConnectionActuatorStage.Tunnel.takeIf { connectionState == ConnectionState.Error }
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

    val isActivation =
        appStatus?.let {
            resolvePrimaryConnectionAction(connectionState, it) == MainPrimaryConnectionAction.START_CONFIGURED_MODE
        } ?: (status == HomeConnectionActuatorStatus.Open || status == HomeConnectionActuatorStatus.Fault)

    return HomeConnectionActuatorUiState(
        status = status,
        trailingLabel = actuatorTrailingLabel(egressBacked, stringResolver),
        routeLabel = approachSummary?.title ?: routeLabelForMode(mode, settings, stringResolver),
        statusDescription =
            actuatorStatusDescription(
                status = status,
                egressBacked = egressBacked,
                vpnDataPlaneStatus = vpnWarning,
                warningStage = warningStage,
                failedStage = failedStage,
                stringResolver = stringResolver,
            ),
        actionLabel =
            hardKillSwitch.actionLabel.takeIf { hardKillSwitch.blocksDisconnect }
                ?: actuatorActionLabel(status, isActivation, stringResolver),
        faultDetail = actuatorFaultDetail(status, runtime),
        stages = buildActuatorStages(status, warningStage, failedStage, stringResolver),
        activationEnabled = isActivation,
        deactivationEnabled = !isActivation && !hardKillSwitch.blocksDisconnect,
    )
}

private fun buildActuatorStages(
    status: HomeConnectionActuatorStatus,
    warningStage: HomeConnectionActuatorStage?,
    failedStage: HomeConnectionActuatorStage?,
    stringResolver: StringResolver,
): ImmutableList<HomeConnectionActuatorStageUiState> =
    HomeConnectionActuatorStage.entries
        .map { stage ->
            HomeConnectionActuatorStageUiState(
                stage = stage,
                label = stage.label(stringResolver),
                state = stageState(stage, status, warningStage, failedStage),
            )
        }.toImmutableList()

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

private fun stageState(
    stage: HomeConnectionActuatorStage,
    status: HomeConnectionActuatorStatus,
    warningStage: HomeConnectionActuatorStage?,
    failedStage: HomeConnectionActuatorStage?,
): HomeConnectionActuatorStageState =
    when {
        status == HomeConnectionActuatorStatus.Open -> {
            HomeConnectionActuatorStageState.Pending
        }

        status == HomeConnectionActuatorStatus.Fault && stage == failedStage -> {
            HomeConnectionActuatorStageState.Failed
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
            vpnDataPlaneActuatorDescription(vpnDataPlaneStatus, stringResolver)
                ?: stringResolver.getString(
                    if (egressBacked) {
                        R.string.home_connection_actuator_state_degraded
                    } else {
                        R.string.home_connection_actuator_state_direct_degraded
                    },
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
    isActivation: Boolean,
    stringResolver: StringResolver,
): String =
    stringResolver.getString(
        when {
            !isActivation && status != HomeConnectionActuatorStatus.Engaging -> {
                R.string.home_connection_actuator_action_deactivate
            }

            status == HomeConnectionActuatorStatus.Engaging -> {
                R.string.home_connection_actuator_action_cancel
            }

            status == HomeConnectionActuatorStatus.Fault -> {
                R.string.home_connection_actuator_action_retry
            }

            else -> {
                R.string.home_connection_actuator_action_activate
            }
        },
    )

private fun HomeConnectionActuatorStage.label(stringResolver: StringResolver): String =
    stringResolver.getString(labelRes())

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
        FailureClass.Compatibility,
        FailureClass.NativeIo,
        FailureClass.Unexpected,
        null,
        -> {
            null
        }
    }
