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
import com.poyka.ripdpi.ui.screens.settings.buildControlPlaneHealthSummary
import com.poyka.ripdpi.ui.screens.status.HardKillSwitchUiState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageUiState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorUiState
import com.poyka.ripdpi.ui.screens.status.StatusDiagnosticsUiState
import com.poyka.ripdpi.ui.screens.status.StatusMode
import com.poyka.ripdpi.ui.screens.status.StatusModeCardUiState
import com.poyka.ripdpi.ui.screens.status.StatusModeCardsInput
import com.poyka.ripdpi.ui.screens.status.StatusModeSummaryFacet
import com.poyka.ripdpi.ui.screens.status.actuatorTrailingLabel
import com.poyka.ripdpi.ui.screens.status.buildHardKillSwitchUiState
import com.poyka.ripdpi.ui.screens.status.buildStatusModeCards
import com.poyka.ripdpi.ui.screens.status.isForeignExitLive
import com.poyka.ripdpi.ui.screens.status.isForeignExitLost
import com.poyka.ripdpi.ui.screens.status.labelRes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
    approachSummary: StatusApproachSummaryUiState?,
): MainUiState {
    val settings = inputs.settings
    val (status, activeMode) = inputs.statusAndMode
    val runtime = inputs.runtime
    val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
    val permissionSummary =
        buildStatusPermissionSummary(
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
    val homeDiagnosticsUiState = StatusDiagnosticsUiState()
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
    homeDiagnostics: StatusDiagnosticsUiState,
    stringResolver: StringResolver,
): ImmutableList<StatusModeCardUiState> =
    buildStatusModeCards(
        StatusModeCardsInput(
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
        if (!card.isActive || card.mode == StatusMode.Diagnostic) {
            card
        } else {
            val applied = buildAppliedConfigurationUiState(inputs, stringResolver)
            card.copy(
                primaryLabel = applied.status,
                summaryFacets =
                    listOfNotNull(
                        applied.confirmedSummary?.let {
                            StatusModeSummaryFacet(
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
    approachSummary: StatusApproachSummaryUiState?,
    hardKillSwitch: HardKillSwitchUiState,
    stringResolver: StringResolver,
): StatusConnectionActuatorUiState =
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

private fun buildStatusPermissionSummary(
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
private fun actuatorIssueDetail(
    status: StatusConnectionActuatorStatus,
    runtime: ConnectionRuntimeState,
    vpnWarning: VpnDataPlaneStatus?,
    warningStage: StatusConnectionActuatorStage?,
    stringResolver: StringResolver,
): String =
    when {
        status == StatusConnectionActuatorStatus.Fault -> {
            runtime.errorMessage.orEmpty()
        }

        // A data-plane warning already states the problem in the headline.
        status == StatusConnectionActuatorStatus.Degraded && vpnWarning == null -> {
            warningStage?.let { stringResolver.getString(it.degradedDetailRes()) }.orEmpty()
        }

        else -> {
            ""
        }
    }

private fun StatusConnectionActuatorStage.degradedDetailRes(): Int =
    when (this) {
        StatusConnectionActuatorStage.Network -> R.string.status_connection_actuator_degraded_network
        StatusConnectionActuatorStage.Dns -> R.string.status_connection_actuator_degraded_dns
        StatusConnectionActuatorStage.Handshake -> R.string.status_connection_actuator_degraded_handshake
        StatusConnectionActuatorStage.Tunnel -> R.string.status_connection_actuator_degraded_tunnel
        StatusConnectionActuatorStage.Route -> R.string.status_connection_actuator_degraded_route
    }

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
    approachSummary: StatusApproachSummaryUiState?,
    appStatus: AppStatus? = null,
    hardKillSwitch: HardKillSwitchUiState = HardKillSwitchUiState(),
    stringResolver: StringResolver,
): StatusConnectionActuatorUiState {
    val mode = if (connectionState == ConnectionState.Connected) activeMode else configuredMode
    val egressBacked = isForeignExitLive(settings, telemetry)
    val vpnWarning = vpnDataPlaneWarning(connectionState, vpnDataPlaneStatus)
    val warningStage =
        (
            StatusConnectionActuatorStage.Route.takeIf { vpnWarning != null }
                ?: StatusConnectionActuatorStage.Network.takeIf { vpnValidationWarning }
                ?: StatusConnectionActuatorStage.Tunnel.takeIf { vpnForwardingWarning }
                ?: telemetryWarningStage(telemetry)
        ).takeIf { connectionState == ConnectionState.Connected }
    val failedStage =
        telemetryFailureStage(telemetry)
            .takeIf { connectionState == ConnectionState.Error }
            ?: StatusConnectionActuatorStage.Tunnel.takeIf { connectionState == ConnectionState.Error }
    val status =
        when {
            connectionState == ConnectionState.Connected && warningStage != null -> {
                StatusConnectionActuatorStatus.Degraded
            }

            connectionState == ConnectionState.Connected -> {
                StatusConnectionActuatorStatus.Locked
            }

            connectionState == ConnectionState.Connecting -> {
                StatusConnectionActuatorStatus.Engaging
            }

            connectionState == ConnectionState.Error -> {
                StatusConnectionActuatorStatus.Fault
            }

            else -> {
                StatusConnectionActuatorStatus.Open
            }
        }

    val isActivation =
        appStatus?.let {
            resolvePrimaryConnectionAction(connectionState, it) == MainPrimaryConnectionAction.START_CONFIGURED_MODE
        } ?: (status == StatusConnectionActuatorStatus.Open || status == StatusConnectionActuatorStatus.Fault)

    return StatusConnectionActuatorUiState(
        status = status,
        trailingLabel = actuatorTrailingLabel(egressBacked, stringResolver),
        routeLabel = routeLabelForMode(mode, settings, stringResolver),
        strategyLabel = approachSummary?.title.orEmpty(),
        strategyFacets = approachSummary?.facets ?: persistentListOf(),
        statusDescription =
            actuatorStatusDescription(
                status = status,
                vpnDataPlaneStatus = vpnWarning,
                warningStage = warningStage,
                failedStage = failedStage,
                stringResolver = stringResolver,
            ),
        actionLabel =
            hardKillSwitch.actionLabel.takeIf { hardKillSwitch.blocksDisconnect }
                ?: actuatorActionLabel(status, isActivation, stringResolver),
        issueDetail = actuatorIssueDetail(status, runtime, vpnWarning, warningStage, stringResolver),
        sessionDuration = runtime.connectionDuration,
        stages = buildActuatorStages(status, warningStage, failedStage, stringResolver),
        activationEnabled = isActivation,
        deactivationEnabled = !isActivation && !hardKillSwitch.blocksDisconnect,
    )
}

private fun buildActuatorStages(
    status: StatusConnectionActuatorStatus,
    warningStage: StatusConnectionActuatorStage?,
    failedStage: StatusConnectionActuatorStage?,
    stringResolver: StringResolver,
): ImmutableList<StatusConnectionActuatorStageUiState> =
    StatusConnectionActuatorStage.entries
        .map { stage ->
            StatusConnectionActuatorStageUiState(
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
            stringResolver.getString(R.string.status_mode_vpn)
        }

        Mode.Proxy -> {
            val ip = settings.proxyIp.ifEmpty { "127.0.0.1" }
            val port = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080"
            "${stringResolver.getString(R.string.status_mode_proxy)} · $ip:$port"
        }
    }

private fun stageState(
    stage: StatusConnectionActuatorStage,
    status: StatusConnectionActuatorStatus,
    warningStage: StatusConnectionActuatorStage?,
    failedStage: StatusConnectionActuatorStage?,
): StatusConnectionActuatorStageState =
    when {
        status == StatusConnectionActuatorStatus.Open -> {
            StatusConnectionActuatorStageState.Pending
        }

        status == StatusConnectionActuatorStatus.Fault && stage == failedStage -> {
            StatusConnectionActuatorStageState.Failed
        }

        status == StatusConnectionActuatorStatus.Degraded && stage == warningStage -> {
            StatusConnectionActuatorStageState.Warning
        }

        status == StatusConnectionActuatorStatus.Locked || status == StatusConnectionActuatorStatus.Degraded -> {
            StatusConnectionActuatorStageState.Complete
        }

        else -> {
            StatusConnectionActuatorStageState.Pending
        }
    }

private fun actuatorStatusDescription(
    status: StatusConnectionActuatorStatus,
    vpnDataPlaneStatus: VpnDataPlaneStatus?,
    warningStage: StatusConnectionActuatorStage?,
    failedStage: StatusConnectionActuatorStage?,
    stringResolver: StringResolver,
): String =
    when (status) {
        StatusConnectionActuatorStatus.Open -> {
            stringResolver.getString(R.string.status_connection_actuator_state_open)
        }

        StatusConnectionActuatorStatus.Engaging -> {
            stringResolver.getString(R.string.status_connection_actuator_state_engaging)
        }

        StatusConnectionActuatorStatus.Locked -> {
            stringResolver.getString(R.string.status_connection_actuator_state_locked)
        }

        StatusConnectionActuatorStatus.Degraded -> {
            vpnDataPlaneActuatorDescription(vpnDataPlaneStatus, stringResolver)
                ?: stringResolver.getString(
                    R.string.status_connection_actuator_state_degraded,
                    warningStage?.label(stringResolver).orEmpty(),
                )
        }

        StatusConnectionActuatorStatus.Fault -> {
            stringResolver.getString(
                R.string.status_connection_actuator_state_fault,
                failedStage?.label(stringResolver).orEmpty(),
            )
        }
    }

private fun actuatorActionLabel(
    status: StatusConnectionActuatorStatus,
    isActivation: Boolean,
    stringResolver: StringResolver,
): String =
    stringResolver.getString(
        when {
            !isActivation && status != StatusConnectionActuatorStatus.Engaging -> {
                R.string.status_connection_actuator_action_deactivate
            }

            status == StatusConnectionActuatorStatus.Engaging -> {
                R.string.status_connection_actuator_action_cancel
            }

            status == StatusConnectionActuatorStatus.Fault -> {
                R.string.status_connection_actuator_action_retry
            }

            else -> {
                R.string.status_connection_actuator_action_activate
            }
        },
    )

private fun StatusConnectionActuatorStage.label(stringResolver: StringResolver): String =
    stringResolver.getString(labelRes())

private fun telemetryWarningStage(telemetry: ServiceTelemetrySnapshot): StatusConnectionActuatorStage? =
    when {
        // Foreign exit lost after connecting (audit P1-1): the base stays up but traffic now
        // egresses direct, so surface a Route-stage warning that flips Locked -> Degraded.
        // The egress-aware predicate lives in isForeignExitLost (ActuatorEgressHonesty) so
        // this aggregator stays free of that feature family.
        isForeignExitLost(telemetry) -> {
            StatusConnectionActuatorStage.Route
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.NetworkHandover ||
            telemetry.networkHandoverState != null -> {
            StatusConnectionActuatorStage.Network
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.DnsInterference ||
            telemetry.tunnelTelemetry.resolverFallbackActive ||
            telemetry.tunnelTelemetry.dnsFailuresTotal > 0 -> {
            StatusConnectionActuatorStage.Dns
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.TlsInterference ||
            !telemetry.proxyTelemetry.lastHandshakeError.isNullOrBlank() -> {
            StatusConnectionActuatorStage.Handshake
        }

        telemetry.runtimeFieldTelemetry.failureClass == FailureClass.TunnelEstablish ||
            telemetry.tunnelTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
            telemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount > 0 -> {
            StatusConnectionActuatorStage.Tunnel
        }

        telemetry.proxyTelemetry.routeChanges > 0 ||
            telemetry.runtimeFieldTelemetry.proxyRouteRetryCount > 0 ||
            !telemetry.proxyTelemetry.lastFallbackAction.isNullOrBlank() -> {
            StatusConnectionActuatorStage.Route
        }

        else -> {
            null
        }
    }

private fun telemetryFailureStage(telemetry: ServiceTelemetrySnapshot): StatusConnectionActuatorStage? =
    when (telemetry.runtimeFieldTelemetry.failureClass) {
        FailureClass.NetworkHandover -> {
            StatusConnectionActuatorStage.Network
        }

        FailureClass.DnsInterference -> {
            StatusConnectionActuatorStage.Dns
        }

        FailureClass.TlsInterference,
        FailureClass.FingerprintPolicy,
        -> {
            StatusConnectionActuatorStage.Handshake
        }

        FailureClass.TunnelEstablish,
        FailureClass.WarpProvisioning,
        FailureClass.WarpEndpoint,
        -> {
            StatusConnectionActuatorStage.Tunnel
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
