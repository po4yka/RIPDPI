package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeConnectionMeasurementsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `empty default shows traffic without nominal RTT jitter or sample claims`() {
        render(ConnectionQualitySnapshot())
        node(R.string.home_quality_waiting).assertExists()
        node(R.string.home_quality_traffic_total).assertExists()
        node(R.string.vpn_quality_strip_nominal_title).assertDoesNotExist()
        node(R.string.vpn_quality_metric_loss).assertDoesNotExist()
        node(R.string.vpn_quality_metric_rtt_p50).assertDoesNotExist()
        node(R.string.vpn_quality_metric_jitter).assertDoesNotExist()
        composeRule.onNodeWithText("0 samples", substring = true).assertDoesNotExist()
    }

    @Test
    fun `measured loss only shows fractional loss but no synthetic RTT or jitter`() {
        render(ConnectionQualitySnapshot(lossPct = 0.25f), currentSource())
        node(R.string.home_quality_partial).assertExists()
        node(R.string.vpn_quality_metric_loss).assertExists()
        node(R.string.vpn_quality_metric_rtt_p50).assertDoesNotExist()
        node(R.string.vpn_quality_metric_jitter).assertDoesNotExist()
        composeRule
            .onNodeWithText(
                RuntimeEnvironment.getApplication().getString(R.string.home_quality_metric_loss_format, 0.25f),
            ).assertExists()
    }

    @Test
    fun `measured zero loss window is kept without RTT samples`() {
        render(ConnectionQualitySnapshot(windowStartAtMs = 1_000L), currentSource())
        node(R.string.vpn_quality_metric_loss).assertExists()
        node(R.string.vpn_quality_metric_rtt_p50).assertDoesNotExist()
        node(R.string.vpn_quality_strip_nominal_title).assertDoesNotExist()
    }

    @Test
    fun `actual RTT samples rounded to zero remain visible`() {
        render(ConnectionQualitySnapshot(sampleCount = 1L), currentSource())
        node(R.string.vpn_quality_metric_rtt_p50).assertExists()
        node(R.string.vpn_quality_metric_jitter).assertExists()
        composeRule
            .onNodeWithText(
                RuntimeEnvironment.getApplication().getString(R.string.home_quality_samples, 1L),
            ).assertExists()
        node(R.string.home_quality_partial).assertExists()
    }

    @Test
    fun `stopped populated source shows last values not connected quality nominal`() {
        render(
            ConnectionQualitySnapshot(sampleCount = 20L, rttP50Ms = 30L),
            currentSource().copy(serviceStatus = AppStatus.Halted),
        )
        node(R.string.home_quality_last_values).assertExists()
        node(R.string.measurement_stopped).assertExists()
        node(R.string.vpn_quality_strip_nominal_title).assertDoesNotExist()
        node(R.string.vpn_quality_metric_rtt_p50).assertExists()
    }

    @Test
    fun `only current sufficiently sampled quality can get nominal assessment`() {
        val quality = ConnectionQualitySnapshot(sampleCount = 12L)
        assertEquals(
            R.string.vpn_quality_strip_nominal_title,
            resolveHomeMeasurementSummary(quality, RuntimeMeasurementFreshness.Current).title,
        )
        listOf(
            null,
            RuntimeMeasurementFreshness.Unknown,
            RuntimeMeasurementFreshness.Stopped,
            RuntimeMeasurementFreshness.Stale,
            RuntimeMeasurementFreshness.Error,
        ).forEach {
            assertEquals(R.string.home_quality_last_values, resolveHomeMeasurementSummary(quality, it).body)
        }
        assertEquals(
            R.string.home_quality_partial,
            resolveHomeMeasurementSummary(quality.copy(sampleCount = 11L), RuntimeMeasurementFreshness.Current).body,
        )
    }

    private fun render(
        quality: ConnectionQualitySnapshot,
        source: RuntimeMeasurementSource? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                HomeConnectionMeasurements(
                    quality,
                    source,
                    connected = true,
                    dataTransferred = 204_000L,
                    onReprobe = {},
                )
            }
        }
    }

    private fun currentSource() =
        RuntimeMeasurementSource(
            "proxy",
            System.currentTimeMillis(),
            AppStatus.Running,
            RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot),
        )

    private fun node(resource: Int) =
        composeRule.onNodeWithText(RuntimeEnvironment.getApplication().getString(resource))
}
