package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.resolveConnectionQuality
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.service.telemetry.measurementSource
import com.poyka.ripdpi.ui.components.homePreviewActuatorState
import com.poyka.ripdpi.ui.screens.home.HomeConnectionMeasurements
import com.poyka.ripdpi.ui.screens.home.HomeScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeMeasurementsScreenshotTest {
    @Test fun emptyLight() = captureEmpty("empty_light")

    @Test fun emptyDark() = captureEmpty("empty_dark", dark = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun emptyRtl() = captureEmpty("empty_rtl", direction = LayoutDirection.Rtl)

    @Test fun emptyMaximumFont() = captureEmpty("empty_max_font", fontScale = 2f)

    @Test fun lossOnly() =
        capturePartial(
            "loss_only",
            ConnectionQualitySnapshot(
                lossPct = 0.25f,
                windowStartAtMs =
                    SnapshotTime - 5_000L,
            ),
        )

    @Test fun measuredZeroRtt() =
        capturePartial("measured_zero_rtt", ConnectionQualitySnapshot(sampleCount = 1L), dark = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun measuredZeroLossRtlMaximumFont() =
        capturePartial(
            "zero_loss_rtl_max_font",
            ConnectionQualitySnapshot(
                windowStartAtMs =
                    SnapshotTime - 5_000L,
            ),
            direction = LayoutDirection.Rtl,
            fontScale = 2f,
        )

    @Test fun stoppedMeasurements() =
        capturePartial(
            "stopped_measurements",
            ConnectionQualitySnapshot(sampleCount = 20L, rttP50Ms = 30L),
            stopped = true,
        )

    private fun captureEmpty(
        name: String,
        dark: Boolean = false,
        direction: LayoutDirection? = null,
        fontScale: Float = 1f,
    ) {
        val selected =
            resolveConnectionQuality(
                ServiceTelemetrySnapshot(
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            connectionQuality = ConnectionQualitySnapshot(),
                        ),
                ),
            )
        captureSingle(
            name,
            widthDp = 420,
            heightDp =
                if (fontScale >
                    1f
                ) {
                    2200
                } else {
                    1300
                },
            darkMode = dark,
            fontScale = fontScale,
            layoutDirection = direction,
            testClassFqn = javaClass.name,
        ) {
            HomeScreen(
                uiState =
                    MainUiState(
                        appStatus = AppStatus.Running,
                        connectionState = ConnectionState.Connected,
                        connectionActuator =
                            homePreviewActuatorState(HomeConnectionActuatorStatus.Locked),
                        connectionQuality = selected?.connectionQuality,
                        dataTransferred = 204_000L,
                    ),
                onToggleConnection = {
                },
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    private fun capturePartial(
        name: String,
        quality: ConnectionQualitySnapshot,
        dark: Boolean = false,
        direction: LayoutDirection? = null,
        fontScale: Float = 1f,
        stopped: Boolean = false,
    ) = withUtcScreenshotTime {
        val telemetry =
            ServiceTelemetrySnapshot(
                status = if (stopped) AppStatus.Halted else AppStatus.Running,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        connectionQuality = quality,
                        capturedAt = SnapshotTime,
                    ),
                proxyTelemetryStatus = RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot),
            )
        val selected = resolveConnectionQuality(telemetry)!!
        captureSingle(
            name,
            widthDp = 420,
            heightDp =
                if (fontScale >
                    1f
                ) {
                    1800
                } else {
                    900
                },
            darkMode = dark,
            fontScale = fontScale,
            layoutDirection = direction,
            testClassFqn = javaClass.name,
        ) {
            HomeConnectionMeasurements(
                selected.connectionQuality,
                selected.measurementSource(
                    telemetry,
                ),
                connected = !stopped,
                dataTransferred = 204_000L,
                onReprobe = {
                },
                now = SnapshotTime,
            )
        }
    }

    private companion object {
        const val SnapshotTime = 1_700_000_300_000L
    }
}
