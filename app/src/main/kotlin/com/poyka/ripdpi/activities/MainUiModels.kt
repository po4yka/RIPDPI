package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.AndroidHardKillSwitchSnapshot
import com.poyka.ripdpi.ui.navigation.Route
import kotlinx.collections.immutable.ImmutableList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

sealed interface MainEffect {
    data class RequestPermission(
        val kind: PermissionKind,
        val payload: Intent? = null,
    ) : MainEffect

    data class OpenAppSettings(
        val intent: Intent,
    ) : MainEffect

    data object ShowVpnPermissionDialog : MainEffect

    data class ShowError(
        val message: String,
        val supportCode: String? = null,
    ) : MainEffect

    data class ShareDiagnosticsArchive(
        val absolutePath: String,
        val fileName: String,
    ) : MainEffect

    data class SaveDiagnosticsArchive(
        val request: com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest,
    ) : MainEffect

    data object RelockRequested : MainEffect
}

@Stable
data class MainUiState(
    val settingsLoaded: Boolean = false,
    val appStatus: AppStatus = AppStatus.Halted,
    val activeMode: Mode = Mode.VPN,
    val configuredMode: Mode = Mode.VPN,
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val theme: String = "system",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectionActuator: HomeConnectionActuatorUiState = HomeConnectionActuatorUiState(),
    val connectionDuration: Duration = ZERO,
    val dataTransferred: Long = 0L,
    val errorMessage: String? = null,
    val permissionSummary: PermissionSummaryUiState = PermissionSummaryUiState(),
    val hardKillSwitch: HardKillSwitchUiState = HardKillSwitchUiState(),
    val approachSummary: HomeApproachSummaryUiState? = null,
    val homeDiagnostics: HomeDiagnosticsUiState = HomeDiagnosticsUiState(),
    val modeCards: ImmutableList<HomeModeCardUiState> = DefaultHomeModeCards,
    val controlPlaneHealthSummary: ControlPlaneHealthSummaryUiModel? = null,
    val connectionQuality: ConnectionQualitySnapshot? = null,
    val networkCondition: com.poyka.ripdpi.services.network.NetworkCondition =
        com.poyka.ripdpi.services.network.NetworkCondition.Normal,
    /**
     * Live embedded-Xray provider engine snapshot, surfaced DISTINCTLY from the
     * tunnel data plane on Home (see [com.poyka.ripdpi.ui.screens.home.HomeXrayProviderBanner]).
     * Null on the native provider path or when no Xray session is active.
     */
    val xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot? = null,
) {
    val localBypassCard: HomeModeCardUiState
        get() =
            modeCards.firstOrNull { it.mode == HomeMode.LocalDpiBypass } ?: HomeModeCardUiState(
                mode = HomeMode.LocalDpiBypass,
            )

    val vpnCard: HomeModeCardUiState
        get() =
            modeCards.firstOrNull { it.mode == HomeMode.RemoteVpn } ?: HomeModeCardUiState(
                mode = HomeMode.RemoteVpn,
            )

    val diagnosticCard: HomeModeCardUiState
        get() =
            modeCards.firstOrNull { it.mode == HomeMode.Diagnostic } ?: HomeModeCardUiState(
                mode = HomeMode.Diagnostic,
            )

    val isConnected: Boolean
        get() = connectionState == ConnectionState.Connected

    val isConnecting: Boolean
        get() = connectionState == ConnectionState.Connecting
}

internal fun shouldStopLocalBypassToggle(state: MainUiState): Boolean =
    state.appStatus == AppStatus.Running &&
        (state.activeMode == Mode.Proxy || state.localBypassCard.isActive)

@Immutable
data class HomeApproachSummaryUiState(
    val title: String,
    val verification: String,
    val successRate: String,
    val supportingText: String,
)

internal data class ConnectionRuntimeState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val errorMessage: String? = null,
    val connectingStartedAtMs: Long? = null,
    val connectionStartedAtMs: Long? = null,
    val baselineTransferredBytes: Long = 0L,
    val dataTransferred: Long = 0L,
    val connectionDuration: Duration = ZERO,
)

internal data class PermissionRuntimeState(
    val snapshot: com.poyka.ripdpi.permissions.PermissionSnapshot =
        com.poyka.ripdpi.permissions
            .PermissionSnapshot(),
    val issue: PermissionIssueUiState? = null,
)

internal data class MainUiInputs(
    val settings: AppSettings,
    val statusAndMode: Pair<AppStatus, Mode>,
    val runtime: ConnectionRuntimeState,
    val telemetry: ServiceTelemetrySnapshot,
    val permissions: PermissionRuntimeState,
    val hardKillSwitch: AndroidHardKillSwitchSnapshot,
    val approachStats: List<com.poyka.ripdpi.diagnostics.BypassApproachSummary>,
    val hostPackCatalog: HostPackCatalogUiState,
    val strategyPackRuntimeState: StrategyPackRuntimeState,
    val pathValidation: NetworkPathValidationEvidence?,
)

internal data class MainUiInputsBase(
    val settings: AppSettings,
    val statusAndMode: Pair<AppStatus, Mode>,
    val runtime: ConnectionRuntimeState,
    val telemetry: ServiceTelemetrySnapshot,
    val permissions: PermissionRuntimeState,
    val approachStats: List<com.poyka.ripdpi.diagnostics.BypassApproachSummary>,
    val pathValidation: NetworkPathValidationEvidence? = null,
)

internal fun calculateTransferredBytes(
    totalBytes: Long,
    baselineBytes: Long,
): Long = (totalBytes - baselineBytes).coerceAtLeast(0L)

internal fun shouldPollConnectionMetrics(connectionState: ConnectionState): Boolean =
    connectionState == ConnectionState.Connected

@Immutable
data class MainStartupState(
    val readiness: com.poyka.ripdpi.AppStartupReadinessState =
        com.poyka.ripdpi.AppStartupReadinessState.Pending,
    val theme: String = "system",
    val startDestination: Route = Route.Home,
) {
    val isReady: Boolean
        get() = readiness == com.poyka.ripdpi.AppStartupReadinessState.Ready
}

internal fun resolveStartupDestination(settings: AppSettings): Route =
    when {
        !settings.onboardingComplete -> Route.Onboarding
        settings.biometricEnabled -> Route.BiometricPrompt
        else -> Route.Home
    }
