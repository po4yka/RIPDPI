package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LatencyDistributions
import com.poyka.ripdpi.data.LatencyPercentiles
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMeasurementTest {
    private val status = RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot)

    @Test
    fun `latency and dns keep their own source timestamps and states`() {
        val telemetry =
            ServiceTelemetrySnapshot(
                status = AppStatus.Running,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        capturedAt = 1_000L,
                        latencyDistributions =
                            LatencyDistributions(
                                tcpConnect =
                                    LatencyPercentiles(
                                        p50 = 10,
                                        p95 = 20,
                                        p99 = 30,
                                        min = 1,
                                        max = 35,
                                        count = 2,
                                    ),
                            ),
                        dnsQueriesTotal = 2L,
                    ),
                proxyTelemetryStatus = status,
                tunnelTelemetry = NativeRuntimeSnapshot(source = "tunnel", capturedAt = 2_000L, dnsQueriesTotal = 8L),
                tunnelTelemetryStatus = RuntimeTelemetryStatus(RuntimeTelemetryState.EngineError),
                updatedAt = 9_000L,
            )
        val insights = projectRuntimeTelemetryInsights(telemetry)
        assertEquals("proxy", insights.latencySource?.source)
        assertEquals(1_000L, insights.latencySource?.capturedAt)
        assertEquals(status, insights.latencySource?.telemetryStatus)
        assertEquals("tunnel", insights.dnsSource?.source)
        assertEquals(2_000L, insights.dnsSource?.capturedAt)
        assertEquals(RuntimeTelemetryState.EngineError, insights.dnsSource?.telemetryStatus?.state)
        assertEquals(8L, insights.dnsCounters?.queriesTotal)
        val empty = projectRuntimeTelemetryInsights(ServiceTelemetrySnapshot())
        assertNull(empty.latencySource)
        assertNull(empty.dnsSource)
        val failureOnly =
            projectRuntimeTelemetryInsights(
                telemetry.copy(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            dnsFailuresTotal = 1L,
                            capturedAt = 7_000L,
                        ),
                    tunnelTelemetry = NativeRuntimeSnapshot.idle(source = "tunnel"),
                ),
            )
        assertEquals(1L, failureOnly.dnsCounters?.failuresTotal)
        assertEquals(7_000L, failureOnly.dnsSource?.capturedAt)
    }

    @Test
    fun `empty proxy histogram does not shadow observed tunnel histogram`() {
        val emptyHistogram = LatencyDistributions(tcpConnect = LatencyPercentiles(0, 0, 0, 0, 0, 0))
        val realHistogram = LatencyDistributions(tcpConnect = LatencyPercentiles(10, 20, 30, 1, 35, 2))
        val projected =
            projectRuntimeTelemetryInsights(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            capturedAt = 1_000L,
                            latencyDistributions = emptyHistogram,
                        ),
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            capturedAt = 2_000L,
                            latencyDistributions = realHistogram,
                        ),
                ),
            )
        assertEquals(realHistogram, projected.latencyDistributions)
        assertEquals("tunnel", projected.latencySource?.source)
        assertEquals(2_000L, projected.latencySource?.capturedAt)
        org.junit.Assert.assertFalse(RuntimeTelemetryInsights(latencyDistributions = emptyHistogram).hasData)
    }

    @Test
    fun `freshness follows conservative producer cadence and handles unavailable sources`() {
        val source = RuntimeMeasurementSource("proxy", 1_000L, AppStatus.Running, status)
        assertEquals(1_000L, RuntimeTelemetrySamplingPolicy.intervalMillis(true))
        assertEquals(5_000L, RuntimeTelemetrySamplingPolicy.intervalMillis(false))
        assertEquals(RuntimeMeasurementFreshness.Current, source.freshness(500L))
        assertEquals(RuntimeMeasurementFreshness.Current, source.freshness(10_999L))
        assertEquals(RuntimeMeasurementFreshness.Stale, source.freshness(11_000L))
        assertEquals(RuntimeMeasurementFreshness.Unknown, source.copy(capturedAt = 0L).freshness(11_000L))
        assertEquals(
            RuntimeMeasurementFreshness.Unknown,
            source.copy(telemetryStatus = RuntimeTelemetryStatus.NoData).freshness(11_000L),
        )
        assertEquals(
            RuntimeMeasurementFreshness.Unknown,
            source.copy(serviceStatus = AppStatus.Reconnecting).freshness(11_000L),
        )
        assertEquals(
            RuntimeMeasurementFreshness.Stopped,
            source.copy(serviceStatus = AppStatus.Halted).freshness(11_000L),
        )
        assertEquals(
            RuntimeMeasurementFreshness.Error,
            source.copy(telemetryStatus = RuntimeTelemetryStatus(RuntimeTelemetryState.EngineError)).freshness(11_000L),
        )
    }
}
