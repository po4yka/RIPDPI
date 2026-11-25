package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayTunnelTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayTunnelHandoffTest {
    @Test
    fun `native kind keeps native upstream`() {
        val upstream = XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Native)
        assertEquals(TunnelUpstream.Native, upstream)
    }

    @Test
    fun `xray kind points tunnel at loopback inbound on configured port`() {
        val config = XrayProviderConfig(localInboundPort = 10808)
        val upstream = XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Xray, config)

        assertTrue(upstream is TunnelUpstream.Xray)
        upstream as TunnelUpstream.Xray
        assertEquals("127.0.0.1", upstream.host)
        assertEquals(10808, upstream.port)
        assertEquals(DnsLoopOwner.Tunnel, upstream.dnsOwner)
    }

    @Test
    fun `xray inbound always binds loopback never a routable host`() {
        val upstream =
            XrayTunnelHandoff.resolveUpstream(
                VpnProviderKind.Xray,
                XrayProviderConfig(localInboundPort = 23456),
            ) as TunnelUpstream.Xray
        assertEquals(XrayTunnelHandoff.LoopbackHost, upstream.host)
    }

    @Test
    fun `set-tun-fd topology is rejected as unimplemented follow-up`() {
        val config = XrayProviderConfig(tunnelTopology = XrayTunnelTopology.LibXraySetTunFd)
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Xray, config)
            }
        assertTrue(ex.message!!.contains("LibXraySetTunFd"))
    }

    @Test
    fun `xray upstream rejects non-loopback host`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelUpstream.Xray(host = "10.0.0.1", port = 10808, dnsOwner = DnsLoopOwner.Tunnel)
        }
    }

    @Test
    fun `xray upstream rejects split DNS model`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelUpstream.Xray(host = "127.0.0.1", port = 10808, dnsOwner = DnsLoopOwner.XrayDns)
        }
    }
}
