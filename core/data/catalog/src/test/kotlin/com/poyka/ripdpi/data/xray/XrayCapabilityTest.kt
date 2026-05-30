package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for the capability-label derivation surfaced in the import/selection UI. */
class XrayCapabilityTest {
    private fun realityProfile(udp: Boolean = true): XrayProfile =
        XrayProfile(
            name = "reality",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 443,
                    uuid = "550e8400-e29b-41d4-a716-446655440000",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = "pbk",
                            serverName = "www.cloudflare.com",
                        ),
                ),
            inbound = XrayProfile.LocalInbound(udpEnabled = udp),
        )

    @Test
    fun realityProfileAlwaysCarriesVpnRelayAndAntiDpi() {
        val caps = XrayCapability.forProfile(realityProfile())
        assertTrue(caps.contains(XrayCapability.VPN_PRIVACY))
        assertTrue(caps.contains(XrayCapability.RELAY))
        assertTrue(caps.contains(XrayCapability.ANTI_DPI))
        assertTrue(caps.contains(XrayCapability.DNS_PROTECTION))
    }

    @Test
    fun realtimeMediaTracksUdpInbound() {
        assertTrue(XrayCapability.forProfile(realityProfile(udp = true)).contains(XrayCapability.REALTIME_MEDIA))
        assertFalse(XrayCapability.forProfile(realityProfile(udp = false)).contains(XrayCapability.REALTIME_MEDIA))
    }

    @Test
    fun dnsProtectionRequiresUpstreamServers() {
        val noDns = realityProfile().copy(dns = XrayProfile.DnsSettings(servers = emptyList()))
        assertFalse(XrayCapability.forProfile(noDns).contains(XrayCapability.DNS_PROTECTION))
    }

    @Test
    fun capabilityOrderIsDeterministic() {
        val caps = XrayCapability.forProfile(realityProfile())
        // VPN_PRIVACY and RELAY always lead, in declaration order.
        assertEquals(XrayCapability.VPN_PRIVACY, caps[0])
        assertEquals(XrayCapability.RELAY, caps[1])
    }
}
