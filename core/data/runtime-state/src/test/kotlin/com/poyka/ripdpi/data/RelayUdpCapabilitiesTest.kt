package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUdpCapabilitiesTest {
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
                    ),
                )
            }
        }
    }

    @Test
    fun `TCP only and unknown kinds cannot acquire UDP from a Reality flow`() {
        listOf(
            RelayKindOff,
            RelayKindVless,
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
    }
}
