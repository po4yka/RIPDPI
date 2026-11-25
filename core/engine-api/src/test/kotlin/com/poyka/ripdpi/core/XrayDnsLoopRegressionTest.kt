package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FakeManagedTunnel
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayTunnelTopology
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayDnsLoopRegressionTest {
    private val renderedConfig =
        """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[""" +
            """{"id":"d0c0ffee-dead-beef-cafe-000000000001","flow":"xtls-rprx-vision"}]}]}}]}"""

    private fun orchestrator(
        bridge: FakeXrayNativeBridge,
        tunnel: FakeManagedTunnel,
    ): XrayProviderOrchestrator =
        XrayProviderOrchestrator(
            xrayRuntimeFactory = { cfg ->
                RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined), cfg)
            },
            tunnel = tunnel,
            protectController = XrayProtectController { true },
            renderedConfigProvider = { renderedConfig },
        )

    @Test
    fun `resolved xray upstream always pins DNS ownership to the tunnel`() {
        for (port in listOf(1, 1080, 10808, 20808, 65535)) {
            val upstream =
                XrayTunnelHandoff.resolveUpstream(
                    VpnProviderKind.Xray,
                    XrayProviderConfig(localInboundPort = port),
                )
            assertTrue(upstream is TunnelUpstream.Xray)
            upstream as TunnelUpstream.Xray
            assertEquals("DNS must stay tunnel-owned at port $port", DnsLoopOwner.Tunnel, upstream.dnsOwner)
            assertEquals(XrayTunnelHandoff.LoopbackHost, upstream.host)
        }
    }

    @Test
    fun `the split XrayDns model is not constructible for the bridged topology`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                TunnelUpstream.Xray(
                    host = XrayTunnelHandoff.LoopbackHost,
                    port = 10808,
                    dnsOwner = DnsLoopOwner.XrayDns,
                )
            }
        assertTrue(
            "rejection must name the single-source-of-truth rule",
            ex.message!!.contains("single source of truth"),
        )
    }

    @Test
    fun `set-tun-fd topology - where xray would own DNS - is refused not silently bridged`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                XrayTunnelHandoff.resolveUpstream(
                    VpnProviderKind.Xray,
                    XrayProviderConfig(tunnelTopology = XrayTunnelTopology.LibXraySetTunFd),
                )
            }
        assertTrue(ex.message!!.contains("LibXraySetTunFd"))
    }

    @Test
    fun `bridged xray session resolves DNS via the tunnel end to end`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808)))

            val upstream = tunnel.lastUpstream as TunnelUpstream.Xray
            assertEquals(DnsLoopOwner.Tunnel, upstream.dnsOwner)
            assertEquals(XrayTunnelHandoff.LoopbackHost, upstream.host)
            assertEquals(1, tunnel.startCount)
        }

    @Test
    fun `native route carries no DNS owner and keeps the native resolution path`() {
        val upstream = XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Native)
        assertEquals(TunnelUpstream.Native, upstream)
    }
}
