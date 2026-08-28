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

/**
 * DNS-loop regression matrix for the Xray provider bridge.
 *
 * The hazard: if provider/bootstrap DNS is resolved by an Xray outbound rather
 * than by the RIPDPI tunnel, a second uncoordinated DNS path appears. In the
 * worst case an unprotected DNS query re-enters the TUN fd (packet loop); in
 * the milder case it splits caching/leak/strategy behaviour. The bridge design
 * forbids this by pinning DNS resolution ownership to the tunnel
 * ([DnsLoopOwner.Tunnel]) for the only supported topology
 * ([XrayTunnelTopology.TunToLocalInbound]).
 *
 * These tests lock that invariant from every angle so a refactor cannot
 * silently re-introduce the split-DNS model.
 */
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
        // Sweep a range of inbound ports; DNS owner is invariant.
        for (port in listOf(1, 1080, 10808, 20808, 65535)) {
            val upstream =
                XrayTunnelHandoff.resolveUpstream(
                    VpnProviderKind.Xray,
                    XrayProviderConfig(localInboundPort = port),
                )
            assertTrue(upstream is TunnelUpstream.Xray)
            upstream as TunnelUpstream.Xray
            assertEquals("DNS must stay tunnel-owned at port $port", DnsLoopOwner.Tunnel, upstream.dnsOwner)
            // The inbound is loopback so even the resolved connect targets never
            // leave the device unprotected through this endpoint.
            assertEquals(XrayTunnelHandoff.LoopbackHost, upstream.host)
        }
    }

    @Test
    fun `the split XrayDns model is not constructible for the bridged topology`() {
        // Directly attempting to build an Xray upstream that lets Xray own DNS
        // must be rejected at construction — there is no path to a split model.
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
        // The only topology where Xray could own DNS (SetTunFd) is unimplemented
        // and must fail fast rather than fall through to a working-but-leaky path.
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

            // The tunnel is the only component pointed anywhere; the endpoint it
            // targets is loopback with tunnel-owned DNS — no Xray DNS outbound
            // path is wired, so a query cannot re-enter the TUN via Xray.
            val upstream = tunnel.lastUpstream as TunnelUpstream.Xray
            assertEquals(DnsLoopOwner.Tunnel, upstream.dnsOwner)
            assertEquals(XrayTunnelHandoff.LoopbackHost, upstream.host)
            assertEquals(1, tunnel.startCount)
        }

    @Test
    fun `native route carries no DNS owner and keeps the native resolution path`() {
        val upstream = XrayTunnelHandoff.resolveUpstream(VpnProviderKind.Native)
        // Native upstream is a marker only; it carries no Xray endpoint and no
        // alternate DNS owner, so the native DNS path is untouched.
        assertEquals(TunnelUpstream.Native, upstream)
    }
}
