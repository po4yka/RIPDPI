package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderConfig
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
        val config = XrayProviderConfig(localInboundPort = 23456)
        val upstream = XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Xray, config)

        assertTrue(upstream is TunnelUpstream.Xray)
        upstream as TunnelUpstream.Xray
        assertEquals("127.0.0.1", upstream.host)
        assertEquals(23456, upstream.port)
        assertEquals(DnsLoopOwner.Tunnel, upstream.dnsOwner)
    }

    @Test
    fun `xray upstream uses the default inbound port when no config is supplied`() {
        val upstream = XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Xray) as TunnelUpstream.Xray
        assertEquals(10808, upstream.port)
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
