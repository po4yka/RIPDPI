package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.resolveConnectionQuality
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.service.telemetry.measurementSource
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.ui.components.statusPreviewActuatorState
import com.poyka.ripdpi.ui.screens.status.HardKillSwitchUiState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusPauseUiState
import com.poyka.ripdpi.ui.screens.status.StatusScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The connected Home column: the pause action, a shared advisory card and the measurements strip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeStatusCardsScreenshotTest {
    @Test fun connectedLight() = capture("connected_light")

    @Test fun connectedDark() = capture("connected_dark", dark = true)

    @Test fun connectedMaximumFont() = capture("connected_max_font", fontScale = 2f)

    @Test fun lockdownUnknownLight() = captureLockdownUnknown("lockdown_unknown_light")

    @Test fun lockdownUnknownDark() = captureLockdownUnknown("lockdown_unknown_dark", dark = true)

    /** The one-line row an unknown system lockdown collapses to, alone under the idle connection control. */
    private fun captureLockdownUnknown(
        name: String,
        dark: Boolean = false,
    ) = withUtcScreenshotTime {
        captureSingle(
            "${name}_${BuildConfig.APP_EXPERIENCE}",
            widthDp = 411,
            heightDp = 800,
            darkMode = dark,
            testClassFqn = javaClass.name,
        ) {
            StatusScreen(
                uiState =
                    MainUiState(
                        connectionActuator = statusPreviewActuatorState(StatusConnectionActuatorStatus.Open),
                        hardKillSwitch =
                            HardKillSwitchUiState(
                                status = AndroidHardKillSwitchStatus.UNKNOWN,
                                label = "System lockdown unknown",
                                summary =
                                    "RIPDPI has not observed Android Always-on and lockdown state yet. " +
                                        "Open VPN settings to confirm the hard kill switch.",
                                actionLabel = "Open VPN settings",
                                visible = true,
                            ),
                    ),
                pause = StatusPauseUiState(),
                onToggleConnection = {},
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) = withUtcScreenshotTime {
        val telemetry =
            ServiceTelemetrySnapshot(
                status = AppStatus.Running,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        connectionQuality =
                            ConnectionQualitySnapshot(
                                sampleCount = 2L,
                                rttP50Ms = 105L,
                                jitterMs = 39L,
                                windowStartAtMs = SnapshotTime - 5_000L,
                            ),
                        capturedAt = SnapshotTime,
                    ),
                proxyTelemetryStatus = RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot),
            )
        val selected = resolveConnectionQuality(telemetry)!!
        captureSingle(
            // Both flavors render Home, with different titles.
            "${name}_${BuildConfig.APP_EXPERIENCE}",
            widthDp = 411,
            heightDp = if (fontScale > 1f) 3000 else 1500,
            darkMode = dark,
            fontScale = fontScale,
            testClassFqn = javaClass.name,
        ) {
            StatusScreen(
                uiState =
                    MainUiState(
                        appStatus = AppStatus.Running,
                        connectionState = ConnectionState.Connected,
                        connectionActuator = statusPreviewActuatorState(StatusConnectionActuatorStatus.Locked),
                        connectionQuality = selected.connectionQuality,
                        connectionQualitySource = selected.measurementSource(telemetry),
                        dataTransferred = 192_000L,
                        hardKillSwitch =
                            HardKillSwitchUiState(
                                status = AndroidHardKillSwitchStatus.NOT_ENABLED,
                                label = "System lockdown not enabled",
                                summary =
                                    "RIPDPI can run, but Android is not blocking traffic outside the VPN " +
                                        "if the tunnel stops.",
                                actionLabel = "Open VPN settings",
                                visible = true,
                            ),
                        permissionSummary =
                            PermissionSummaryUiState(
                                recommendedIssue =
                                    PermissionIssueUiState(
                                        kind = PermissionKind.Notifications,
                                        title = "Notifications are off",
                                        message = "Allow notifications to see the connection status.",
                                        recovery = PermissionRecovery.OpenSettings,
                                        actionLabel = "Allow",
                                        blocking = false,
                                    ),
                                backgroundGuidance =
                                    BackgroundGuidanceUiState(
                                        title = "Background limits",
                                        message = "Some devices stop apps in the background.",
                                    ),
                            ),
                    ),
                pause = StatusPauseUiState(available = true),
                onToggleConnection = {},
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    private companion object {
        const val SnapshotTime = 1_700_000_300_000L
    }
}
