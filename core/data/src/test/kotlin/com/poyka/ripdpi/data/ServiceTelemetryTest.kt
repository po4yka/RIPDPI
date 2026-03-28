package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceTelemetryTest {
    // -- RttBand.fromLatencyMs --

    @Test
    fun `RttBand fromLatencyMs null returns Unknown`() {
        assertEquals(RttBand.Unknown, RttBand.fromLatencyMs(null))
    }

    @Test
    fun `RttBand fromLatencyMs negative returns Unknown`() {
        assertEquals(RttBand.Unknown, RttBand.fromLatencyMs(-1L))
    }

    @Test
    fun `RttBand fromLatencyMs 0 returns Lt50`() {
        assertEquals(RttBand.Lt50, RttBand.fromLatencyMs(0L))
    }

    @Test
    fun `RttBand fromLatencyMs 49 returns Lt50`() {
        assertEquals(RttBand.Lt50, RttBand.fromLatencyMs(49L))
    }

    @Test
    fun `RttBand fromLatencyMs 50 returns Between50And99`() {
        assertEquals(RttBand.Between50And99, RttBand.fromLatencyMs(50L))
    }

    @Test
    fun `RttBand fromLatencyMs 100 returns Between100And249`() {
        assertEquals(RttBand.Between100And249, RttBand.fromLatencyMs(100L))
    }

    @Test
    fun `RttBand fromLatencyMs 250 returns Between250And499`() {
        assertEquals(RttBand.Between250And499, RttBand.fromLatencyMs(250L))
    }

    @Test
    fun `RttBand fromLatencyMs 500 returns Gte500`() {
        assertEquals(RttBand.Gte500, RttBand.fromLatencyMs(500L))
    }

    @Test
    fun `RttBand fromLatencyMs 1000 returns Gte500`() {
        assertEquals(RttBand.Gte500, RttBand.fromLatencyMs(1000L))
    }

    // -- aggregateWinningStrategyFamily --

    @Test
    fun `aggregateWinningStrategyFamily returns null when all null`() {
        assertNull(aggregateWinningStrategyFamily(null, null, null))
    }

    @Test
    fun `aggregateWinningStrategyFamily returns single family`() {
        assertEquals("split", aggregateWinningStrategyFamily("split", null, null))
    }

    @Test
    fun `aggregateWinningStrategyFamily joins distinct families`() {
        assertEquals(
            "split + quic_compat_burst",
            aggregateWinningStrategyFamily("split", "quic_compat_burst", null),
        )
    }

    @Test
    fun `aggregateWinningStrategyFamily deduplicates`() {
        assertEquals("split", aggregateWinningStrategyFamily("split", "split", null))
    }

    @Test
    fun `aggregateWinningStrategyFamily trims and filters blanks`() {
        assertNull(aggregateWinningStrategyFamily("  ", "", null))
        assertEquals("split", aggregateWinningStrategyFamily("  split  ", "  ", null))
    }

    @Test
    fun `aggregateWinningStrategyFamily includes dns family`() {
        assertEquals(
            "split + dns_encrypted_doh",
            aggregateWinningStrategyFamily("split", null, "dns_encrypted_doh"),
        )
    }

    // -- aggregateRttBand --

    @Test
    fun `aggregateRttBand returns Unknown when both Unknown`() {
        assertEquals(RttBand.Unknown, aggregateRttBand(RttBand.Unknown, RttBand.Unknown))
    }

    @Test
    fun `aggregateRttBand returns non-Unknown when one is Unknown`() {
        assertEquals(RttBand.Lt50, aggregateRttBand(RttBand.Lt50, RttBand.Unknown))
        assertEquals(RttBand.Gte500, aggregateRttBand(RttBand.Unknown, RttBand.Gte500))
    }

    @Test
    fun `aggregateRttBand returns the worse (higher ordinal) band`() {
        assertEquals(RttBand.Between100And249, aggregateRttBand(RttBand.Lt50, RttBand.Between100And249))
        assertEquals(RttBand.Gte500, aggregateRttBand(RttBand.Gte500, RttBand.Lt50))
    }

    // -- RuntimeFieldTelemetry computed properties --

    @Test
    fun `RuntimeFieldTelemetry retryCount sums proxy and tunnel retries`() {
        val telemetry = RuntimeFieldTelemetry(
            proxyRouteRetryCount = 3,
            tunnelRecoveryRetryCount = 2,
        )
        assertEquals(5L, telemetry.retryCount)
    }

    @Test
    fun `RuntimeFieldTelemetry winningStrategyFamily delegates to aggregate`() {
        val telemetry = RuntimeFieldTelemetry(
            winningTcpStrategyFamily = "split",
            winningQuicStrategyFamily = "quic_compat_burst",
        )
        assertEquals("split + quic_compat_burst", telemetry.winningStrategyFamily)
    }

    @Test
    fun `RuntimeFieldTelemetry rttBand delegates to aggregate`() {
        val telemetry = RuntimeFieldTelemetry(
            proxyRttBand = RttBand.Lt50,
            resolverRttBand = RttBand.Between250And499,
        )
        assertEquals(RttBand.Between250And499, telemetry.rttBand)
    }

    // -- classifyFailureClass --

    @Test
    fun `classifyFailureClass returns null when no failure`() {
        val idle = NativeRuntimeSnapshot.idle("proxy")
        assertNull(classifyFailureClass(null, idle, idle))
    }

    @Test
    fun `classifyFailureClass classifies TunnelEstablishmentFailed directly`() {
        val idle = NativeRuntimeSnapshot.idle("proxy")
        assertEquals(
            FailureClass.TunnelEstablish,
            classifyFailureClass(FailureReason.TunnelEstablishmentFailed, idle, idle),
        )
    }

    @Test
    fun `classifyFailureClass classifies NativeError with dns text as DnsInterference`() {
        val idle = NativeRuntimeSnapshot.idle("proxy")
        assertEquals(
            FailureClass.DnsInterference,
            classifyFailureClass(FailureReason.NativeError("dns failure detected"), idle, idle),
        )
    }

    @Test
    fun `classifyFailureClass falls back to NativeIo for unrecognized NativeError`() {
        val idle = NativeRuntimeSnapshot.idle("proxy")
        assertEquals(
            FailureClass.NativeIo,
            classifyFailureClass(FailureReason.NativeError("something weird"), idle, idle),
        )
    }

    @Test
    fun `classifyFailureClass reads network handover from tunnel telemetry`() {
        val proxy = NativeRuntimeSnapshot.idle("proxy")
        val tunnel = NativeRuntimeSnapshot.idle("tunnel").copy(networkHandoverClass = "transport_switch")
        assertEquals(
            FailureClass.NetworkHandover,
            classifyFailureClass(null, proxy, tunnel),
        )
    }

    @Test
    fun `classifyFailureClass reads latest error event from telemetry`() {
        val event = NativeRuntimeEvent(
            source = "proxy",
            level = "error",
            message = "connection reset by peer",
            createdAt = 1000L,
        )
        val proxy = NativeRuntimeSnapshot.idle("proxy").copy(nativeEvents = listOf(event))
        val tunnel = NativeRuntimeSnapshot.idle("tunnel")
        assertEquals(
            FailureClass.ResetAbort,
            classifyFailureClass(null, proxy, tunnel),
        )
    }

    // -- FailureClass wire values --

    @Test
    fun `FailureClass wire values are stable`() {
        assertEquals("tunnel_establish", FailureClass.TunnelEstablish.wireValue)
        assertEquals("dns_interference", FailureClass.DnsInterference.wireValue)
        assertEquals("tls_interference", FailureClass.TlsInterference.wireValue)
        assertEquals("timeout", FailureClass.Timeout.wireValue)
        assertEquals("reset_abort", FailureClass.ResetAbort.wireValue)
        assertEquals("network_handover", FailureClass.NetworkHandover.wireValue)
        assertEquals("native_io", FailureClass.NativeIo.wireValue)
        assertEquals("unexpected", FailureClass.Unexpected.wireValue)
    }
}
