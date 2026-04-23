package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings

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
