package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTelemetryTest {
    @Test
    fun `derive runtime field telemetry marks recent dht trigger correlation`() {
        val telemetry =
            deriveRuntimeFieldTelemetry(
                telemetryNetworkFingerprintHash = "network-1",
                winningTcpStrategyFamily = null,
                winningQuicStrategyFamily = null,
                winningDnsStrategyFamily = null,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        lastError = "tls handshake timeout",
                        capturedAt = 605_000,
                    ),
                relayTelemetry = NativeRuntimeSnapshot(source = "relay", capturedAt = 605_000),
                warpTelemetry = NativeRuntimeSnapshot(source = "warp", capturedAt = 605_000),
                tunnelTelemetry =
                    NativeRuntimeSnapshot(
                        source = "tunnel",
                        lastDhtTriggerEndpoint = "134.195.198.23:6881",
                        lastDhtTriggerAt = 600_000,
                        capturedAt = 605_000,
                    ),
                tunnelRecoveryRetryCount = 0,
            )

        assertTrue(telemetry.dhtTriggerCorrelationActive)
        assertTrue(telemetry.dhtTriggerCorrelationReason?.contains("134.195.198.23:6881") == true)
    }

    @Test
    fun `derive runtime field telemetry ignores stale dht trigger observations`() {
        val telemetry =
            deriveRuntimeFieldTelemetry(
                telemetryNetworkFingerprintHash = "network-1",
                winningTcpStrategyFamily = null,
                winningQuicStrategyFamily = null,
                winningDnsStrategyFamily = null,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        lastError = "tls handshake timeout",
                        capturedAt = 1_500_000,
                    ),
                relayTelemetry = NativeRuntimeSnapshot(source = "relay", capturedAt = 1_500_000),
                warpTelemetry = NativeRuntimeSnapshot(source = "warp", capturedAt = 1_500_000),
                tunnelTelemetry =
                    NativeRuntimeSnapshot(
                        source = "tunnel",
                        lastDhtTriggerEndpoint = "134.195.198.23:6881",
                        lastDhtTriggerAt = 600_000,
                        capturedAt = 1_500_000,
                    ),
                tunnelRecoveryRetryCount = 0,
            )

        assertFalse(telemetry.dhtTriggerCorrelationActive)
        assertTrue(telemetry.dhtTriggerCorrelationReason == null)
    }
}
