package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RuntimeTelemetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsMeasurementContextTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `live and history metrics explain totals ranges and published dns latency`() {
        val sample = historyTelemetry().copy(resolverLatencyMs = 13, proxyRouteRetryCount = 2)
        val metrics = support.buildTelemetryLiveMetrics(sample)

        fun metric(resource: Int) = metrics.single { it.label == support.context.getString(resource) }
        assertEquals(
            support.context.getString(R.string.measurement_rtt_band),
            metric(R.string.diagnostics_metric_rtt_band).explanation,
        )
        assertEquals(
            support.context.getString(R.string.measurement_dns_last),
            metric(R.string.diagnostics_metric_dns_latency).explanation,
        )
        assertEquals(
            support.context.getString(R.string.measurement_counter_total),
            metric(R.string.diagnostics_metric_retries).scopeLabel,
        )
        val overview = support.buildOverviewMetrics(DiagnosticsHealth.Healthy, emptyList(), emptyList(), sample)
        assertEquals(support.context.getString(R.string.measurement_loaded_view), overview.first().scopeLabel)
        val tx = overview.single { it.label == support.context.getString(R.string.diagnostics_metric_tx) }
        assertEquals(support.context.getString(R.string.measurement_byte_total), tx.explanation)
        assertTrue(tx.scopeLabel.orEmpty().startsWith("Snapshot:"))
    }

    @Test
    fun `unavailable telemetry never asserts current freshness`() {
        val sample =
            historyTelemetry().copy(
                connectionState = "Running",
                proxyTelemetryState = RuntimeTelemetryState.Snapshot.wireValue,
            )
        assertNull(support.liveUnavailableLabel(sample))
        assertEquals(
            support.context.getString(R.string.measurement_unknown),
            support.liveUnavailableLabel(sample.copy(createdAt = 0L)),
        )
        assertEquals(
            support.context.getString(R.string.measurement_unknown),
            support.liveUnavailableLabel(sample.copy(connectionState = "Reconnecting")),
        )
        assertEquals(
            support.context.getString(R.string.measurement_stopped),
            support.liveUnavailableLabel(sample.copy(connectionState = "Stopped")),
        )
        assertEquals(
            support.context.getString(R.string.measurement_error),
            support.liveUnavailableLabel(
                sample.copy(proxyTelemetryState = RuntimeTelemetryState.EngineError.wireValue),
            ),
        )
    }
}
