package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.freshness
import com.poyka.ripdpi.service.telemetry.measurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MainQualityResolverTest {
    @Test
    fun `empty quality and transferred bytes cannot become quality evidence`() {
        assertNull(resolveConnectionQuality(ServiceTelemetrySnapshot()))
        assertNull(
            resolveConnectionQuality(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            connectionQuality = ConnectionQualitySnapshot(),
                        ),
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            connectionQuality = ConnectionQualitySnapshot(),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `observed proxy quality is available on Home`() {
        val proxy =
            NativeRuntimeSnapshot(source = "proxy", connectionQuality = ConnectionQualitySnapshot(sampleCount = 1L))
        assertSame(proxy, resolveConnectionQuality(ServiceTelemetrySnapshot(proxyTelemetry = proxy)))
    }

    @Test
    fun `empty proxy does not hide observed tunnel source or its freshness`() {
        val tunnel =
            NativeRuntimeSnapshot(
                source = "tunnel",
                connectionQuality = ConnectionQualitySnapshot(sampleCount = 1L, rttP50Ms = 40L),
                capturedAt = 1_000L,
            )
        val telemetry =
            ServiceTelemetrySnapshot(
                status = AppStatus.Running,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        connectionQuality = ConnectionQualitySnapshot(),
                    ),
                tunnelTelemetry = tunnel,
                tunnelTelemetryStatus = RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot),
            )
        val selected = resolveConnectionQuality(telemetry)!!
        assertSame(tunnel, selected)
        val source = selected.measurementSource(telemetry)
        assertEquals("tunnel", source.source)
        assertEquals(1_000L, source.capturedAt)
        assertEquals(RuntimeMeasurementFreshness.Current, source.freshness(10_999L))
        assertEquals(RuntimeMeasurementFreshness.Stale, source.freshness(11_000L))
        assertEquals(
            RuntimeMeasurementFreshness.Stopped,
            selected.measurementSource(telemetry.copy(status = AppStatus.Halted)).freshness(1_000L),
        )
    }

    @Test
    fun `measured zero loss window and positive loss without RTT are retained`() {
        listOf(
            ConnectionQualitySnapshot(windowStartAtMs = 1_000L),
            ConnectionQualitySnapshot(lossPct = 0.25f),
        ).forEach {
            val proxy = NativeRuntimeSnapshot(source = "proxy", connectionQuality = it)
            assertEquals(
                it,
                resolveConnectionQuality(ServiceTelemetrySnapshot(proxyTelemetry = proxy))?.connectionQuality,
            )
        }
    }
}
