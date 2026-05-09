package com.poyka.ripdpi.activities

import com.poyka.ripdpi.config.relay.buildDirectPathCapabilityObservation
import com.poyka.ripdpi.config.relay.buildRelayCapabilityObservation
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigRelayCapabilityObservationTest {
    @Test
    fun `relay capability observation marks healthy masque as quic and udp usable`() {
        val (authority, observation) =
            requireNotNull(
                buildRelayCapabilityObservation(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindMasque,
                            relayMasqueUrl = "https://relay.example/masque",
                        ),
                    telemetry =
                        ServiceTelemetrySnapshot(
                            relayTelemetry =
                                NativeRuntimeSnapshot(
                                    source = "relay",
                                    state = "running",
                                    health = "healthy",
                                    activeSessions = 1,
                                    routeChanges = 0,
                                ),
                        ),
                ),
            )

        assertEquals("https://relay.example/masque", authority)
        assertEquals(true, observation.quicUsable)
        assertEquals(true, observation.udpUsable)
        assertEquals(true, observation.authModeAccepted)
        assertEquals(true, observation.multiplexReusable)
    }

    @Test
    fun `relay capability observation marks failed shadowtls camouflage`() {
        val (_, observation) =
            requireNotNull(
                buildRelayCapabilityObservation(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindShadowTlsV3,
                            relayServer = "relay.example",
                            relayServerPort = "443",
                        ),
                    telemetry =
                        ServiceTelemetrySnapshot(
                            relayTelemetry =
                                NativeRuntimeSnapshot(
                                    source = "relay",
                                    state = "idle",
                                    health = "failed",
                                    lastFailureClass = "tls_interference",
                                ),
                        ),
                ),
            )

        assertEquals(false, observation.shadowTlsCamouflageAccepted)
        assertEquals("tls_interference", observation.repeatedHandshakeFailureClass)
    }

    @Test
    fun `direct path capability observation maps healthy quic proxy telemetry`() {
        val (authority, observation) =
            requireNotNull(
                buildDirectPathCapabilityObservation(
                    telemetry =
                        ServiceTelemetrySnapshot(
                            proxyTelemetry =
                                NativeRuntimeSnapshot(
                                    source = "proxy",
                                    state = "running",
                                    health = "healthy",
                                    lastHost = "direct.example",
                                    protocolKind = "quic",
                                    udpCapable = true,
                                    totalSessions = 2,
                                ),
                        ),
                ),
            )

        assertEquals("direct.example", authority)
        assertEquals(true, observation.quicUsable)
        assertEquals(true, observation.udpUsable)
        assertEquals(true, observation.multiplexReusable)
    }
}
