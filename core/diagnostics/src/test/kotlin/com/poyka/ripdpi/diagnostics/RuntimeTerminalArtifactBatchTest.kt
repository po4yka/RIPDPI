package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTerminalArtifactBatchTest {
    @Test
    fun `terminal batch normalizes runtime protect failures without producer details`() {
        val canary = "privacy-canary-protect-detail"
        val batch =
            buildPrivacySafeTerminalArtifactBatch(
                connectionSessionId = "session-safe",
                typedEvents = emptyList(),
                nativeEvents =
                    listOf(
                        NativeRuntimeEvent(
                            source = "vpn_protect",
                            level = "debug",
                            message = "vpn protect backend=uds outcome=rejected",
                            createdAt = 11L,
                            kind = "vpn_protect",
                            runtimeId = canary,
                            mode = canary,
                            policySignature = canary,
                            fingerprintHash = canary,
                            subsystem = "protect",
                        ),
                        NativeRuntimeEvent(
                            source = "vpn_protect",
                            level = "debug",
                            message = "vpn protect backend=uds outcome=error detail=$canary",
                            createdAt = 12L,
                            kind = "vpn_protect",
                            subsystem = "protect",
                        ),
                    ),
                telemetrySample = null,
            )

        assertEquals(1, batch.events.size)
        assertEquals("service", batch.events.single().source)
        assertEquals("warn", batch.events.single().level)
        assertEquals("protect", batch.events.single().subsystem)
        assertEquals("event=protect_failed event_kind=protect_failure", batch.events.single().message)
        assertFalse(RuntimeHistoryJson.encodeToString(batch).contains(canary))
    }

    @Test
    fun `terminal batch serializes only privacy safe typed evidence`() {
        val canary = "privacy-canary-routing-data"
        val batch =
            buildPrivacySafeTerminalArtifactBatch(
                connectionSessionId = "session-safe",
                typedEvents =
                    listOf(
                        NativeSessionEventEntity(
                            id = "typed_runtime_state:dns:session-safe",
                            sessionId = null,
                            connectionSessionId = "session-safe",
                            source = "service_telemetry_state",
                            level = "warn",
                            message =
                                "event=dns_runtime_state evidence=dns_counter_transition_v1 " +
                                    "state=failure_threshold",
                            createdAt = 10L,
                            runtimeId = canary,
                            policySignature = canary,
                            fingerprintHash = canary,
                            subsystem = "dns",
                        ),
                    ),
                nativeEvents =
                    listOf(
                        NativeRuntimeEvent(
                            source = canary,
                            level = "error",
                            message =
                                "state=outbound_only mode=vpn generation=4 final=true endpoint=$canary",
                            createdAt = 11L,
                            runtimeId = canary,
                            policySignature = canary,
                            fingerprintHash = canary,
                            kind = "data_plane_final",
                            subsystem = canary,
                        ),
                    ),
                telemetrySample = terminalSample(canary),
            )

        val encoded = RuntimeHistoryJson.encodeToString(batch)
        assertFalse(encoded.contains(canary))
        assertEquals(2, batch.events.size)
        assertTrue(batch.events.all { event -> event.policySignature == null && event.fingerprintHash == null })
        assertEquals(
            "state=outbound_only mode=vpn generation=4 final=true event_kind=data_plane_final",
            batch.events.last().message,
        )
        assertEquals(terminalDataPlaneEventId("session-safe"), batch.events.last().id)
        assertNull(batch.telemetrySample?.publicIp)
        assertNull(batch.telemetrySample?.resolverEndpoint)
        assertNull(batch.telemetrySample?.proxyTelemetryMessage)
        assertEquals("unknown", batch.telemetrySample?.networkType)
    }

    private fun terminalSample(canary: String): TelemetrySampleEntity =
        TelemetrySampleEntity(
            id = "runtime_terminal_sample:session-safe",
            sessionId = canary,
            connectionSessionId = "session-safe",
            activeMode = "VPN",
            connectionState = canary,
            networkType = canary,
            publicIp = canary,
            telemetryNetworkFingerprintHash = canary,
            winningTcpStrategyFamily = canary,
            winningQuicStrategyFamily = canary,
            resolverId = canary,
            resolverProtocol = canary,
            resolverEndpoint = canary,
            resolverFallbackReason = canary,
            networkHandoverClass = canary,
            networkHandoverState = canary,
            proxyTelemetryMessage = canary,
            relayTelemetryMessage = canary,
            warpTelemetryMessage = canary,
            tunnelTelemetryMessage = canary,
            lastFailureClass = canary,
            lastFallbackAction = canary,
            txPackets = 1L,
            txBytes = 2L,
            rxPackets = 3L,
            rxBytes = 4L,
            relayProtocolKind = canary,
            createdAt = 12L,
        )
}
