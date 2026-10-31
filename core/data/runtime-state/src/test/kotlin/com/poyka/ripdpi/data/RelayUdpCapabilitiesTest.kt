package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUdpCapabilitiesTest {
    @Test
    fun `Shadowsocks UDP requires a supported SS2022 method`() {
        listOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "", "2022-unknown", null).forEach { method ->
            assertFalse(
                method,
                relayProfileSupportsUdpAssociation(
                    RelayKindShadowsocks,
                    true,
                    RelayVlessTransportRealityTcp,
                    RelayVlessFlowVision,
                    method,
                ),
            )
        }
        listOf(
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
        ).forEach { method ->
            assertTrue(
                method,
                relayProfileSupportsUdpAssociation(
                    RelayKindShadowsocks,
                    true,
                    RelayVlessTransportRealityTcp,
                    RelayVlessFlowVision,
                    " ${method.uppercase()} ",
                ),
            )
            assertFalse(
                method,
                relayProfileSupportsUdpAssociation(
                    RelayKindShadowsocks,
                    false,
                    RelayVlessTransportRealityTcp,
                    RelayVlessFlowVision,
                    method,
                ),
            )
        }
    }

    @Test
    fun `seven native UDP kinds preserve support and explicit disable`() {
        val supported =
            listOf(
                RelayKindVlessReality,
                RelayKindHysteria2,
                RelayKindMasque,
                RelayKindAnyTls,
                RelayKindShadowsocks,
                RelayKindTrojan,
                RelayKindTuicV5,
            )
        supported.forEach { kind ->
            assertTrue(kind, relayKindSupportsUdpAssociation(kind))
            listOf(true, false).forEach { enabled ->
                assertEquals(
                    kind,
                    enabled,
                    relayProfileSupportsUdpAssociation(
                        kind,
                        enabled,
                        RelayVlessTransportRealityTcp,
                        RelayVlessFlowVision,
                        shadowsocksMethod = "2022-blake3-aes-128-gcm",
                    ),
                )
            }
        }
    }

    @Test
    fun `TCP only and unknown kinds cannot acquire UDP from a Reality flow`() {
        listOf(
            RelayKindOff,
            RelayKindChainRelay,
            RelayKindCloudflareTunnel,
            RelayKindShadowTlsV3,
            RelayKindNaiveProxy,
            RelayKindTor,
            RelayKindGoogleAppsScript,
            RelayKindSnowflake,
            RelayKindWebTunnel,
            RelayKindObfs4,
            RelayKindMieru,
            RelayKindSsh,
            "unknown",
            "",
        ).forEach { kind ->
            assertFalse(kind, relayKindSupportsUdpAssociation(kind))
            assertFalse(
                kind,
                relayProfileSupportsUdpAssociation(kind, true, RelayVlessTransportRealityTcp, RelayVlessFlowVision),
            )
        }
    }

    @Test
    fun `TLS VLESS UDP requires xHTTP with the empty flow`() {
        assertTrue(relayKindSupportsUdpAssociation(RelayKindVless))
        listOf("", " ", "none", "off").forEach { flow ->
            assertTrue(flow, relayProfileSupportsUdpAssociation(RelayKindVless, true, RelayVlessTransportXhttp, flow))
            assertFalse(flow, relayProfileSupportsUdpAssociation(RelayKindVless, false, RelayVlessTransportXhttp, flow))
        }
        listOf(RelayVlessFlowVision, RelayVlessFlowVisionUdp443, "unsupported-flow").forEach { flow ->
            assertFalse(flow, relayProfileSupportsUdpAssociation(RelayKindVless, true, RelayVlessTransportXhttp, flow))
            assertFalse(
                flow,
                relayProfileSupportsUdpAssociation(RelayKindVless, true, RelayVlessTransportRealityTcp, flow),
            )
        }
        assertFalse(relayProfileSupportsUdpAssociation(RelayKindVless, true, RelayVlessTransportRealityTcp, ""))
    }

    @Test
    fun `Reality UDP requires explicit TCP transport and a supported nonempty flow`() {
        listOf(RelayVlessFlowVision, RelayVlessFlowVisionUdp443).forEach { flow ->
            assertTrue(
                relayProfileSupportsUdpAssociation(RelayKindVlessReality, true, RelayVlessTransportRealityTcp, flow),
            )
            assertTrue(
                relayProfileSupportsUdpAssociation(
                    RelayKindVlessReality,
                    true,
                    RelayVlessTransportRealityTcp,
                    " $flow ",
                ),
            )
            listOf(RelayVlessTransportXhttp, "", "unknown").forEach { transport ->
                assertFalse(relayProfileSupportsUdpAssociation(RelayKindVlessReality, true, transport, flow))
            }
        }
        listOf("", " ", "unsupported-flow").forEach { flow ->
            assertFalse(
                relayProfileSupportsUdpAssociation(RelayKindVlessReality, true, RelayVlessTransportRealityTcp, flow),
            )
        }
        assertTrue(relayProfileSupportsUdpAssociation(RelayKindVlessReality, true, RelayVlessTransportXhttp, ""))
    }
}
