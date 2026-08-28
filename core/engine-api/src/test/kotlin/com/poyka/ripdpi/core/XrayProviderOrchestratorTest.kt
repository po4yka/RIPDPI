package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FakeManagedTunnel
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayProviderOrchestratorTest {
    private val renderedConfig =
        """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[
           {"id":"d0c0ffee-dead-beef-cafe-000000000001","flow":"xtls-rprx-vision"}]}]}}]}"""

    /**
     * A protect controller that records the order of its invocations into a
     * shared event log, so a test can prove protect was wired before any
     * outbound. Always returns success.
     */
    private class RecordingProtect(
        private val events: MutableList<String>,
    ) : XrayProtectController {
        override fun protect(fd: Int): Boolean {
            events += "protect($fd)"
            return true
        }
    }

    private fun orchestrator(
        bridge: FakeXrayNativeBridge,
        tunnel: FakeManagedTunnel,
        protect: XrayProtectController = XrayProtectController { true },
    ): XrayProviderOrchestrator =
        XrayProviderOrchestrator(
            xrayRuntimeFactory = { cfg ->
                RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined), cfg)
            },
            tunnel = tunnel,
            protectController = protect,
            renderedConfigProvider = { renderedConfig },
        )

    @Test
    fun `failed native cleanup retains the route and tunnel barrier`() =
        runTest {
            val bridge = FakeXrayNativeBridge(stopBehavior = FakeXrayNativeBridge.StopBehavior.Throw)
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)
            val route = ProviderRoute(VpnProviderKind.Xray)
            orch.start(route)
            assertTrue(orch.stop() is HandoffOutcome.Failed)
            assertEquals(route, orch.currentRoute)
            assertEquals(VpnProviderState.Stopping, orch.xrayState)
            assertEquals(0, tunnel.stopCount)
            assertTrue(orch.stop() is HandoffOutcome.Failed)
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Clean
            assertEquals(HandoffOutcome.Stopped, orch.stop())
            assertNull(orch.currentRoute)
            assertEquals(1, tunnel.stopCount)
        }

    @Test
    fun `destroy before lease publication denies the late callback and tunnel handoff`() =
        runTest {
            var lateProtection: Boolean? = null
            var serviceCalls = 0
            val bridge =
                object : FakeXrayNativeBridge() {
                    override fun start(jsonConfig: String): Int {
                        lateProtection = registeredProtectController?.protect(42)
                        return super.start(jsonConfig)
                    }
                }
            val tunnel = FakeManagedTunnel()
            val owner = XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined)
            lateinit var orch: XrayProviderOrchestrator
            orch =
                XrayProviderOrchestrator(
                    xrayRuntimeFactory = { cfg -> RipDpiXrayRuntime(owner, cfg) },
                    tunnel = tunnel,
                    protectController = {
                        serviceCalls++
                        true
                    },
                    renderedConfigProvider = {
                        orch.closeServiceOwner()
                        renderedConfig
                    },
                )
            assertTrue(orch.start(ProviderRoute(VpnProviderKind.Xray)) is HandoffOutcome.Failed)
            assertEquals(false, lateProtection)
            assertEquals(0, serviceCalls)
            assertFalse(checkNotNull(bridge.registeredProtectController).protect(42))
            assertEquals(0, tunnel.startCount)
            assertFalse(owner.isOccupied)
            val starts = bridge.startCount
            assertTrue(orch.start(ProviderRoute(VpnProviderKind.Xray)) is HandoffOutcome.Failed)
            assertEquals(starts, bridge.startCount)
        }

    @Test
    fun `native route keeps native upstream and never touches xray`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Native))

            assertEquals(HandoffOutcome.Running(TunnelUpstream.Native), outcome)
            assertEquals(TunnelUpstream.Native, tunnel.lastUpstream)
            // Native path must not start Xray at all.
            assertEquals(0, bridge.startCount)
            assertEquals(VpnProviderState.Stopped, orch.xrayState)
        }

    @Test
    fun `xray route points tunnel at loopback inbound`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)
            val route = ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808))

            val outcome = orch.start(route)

            assertTrue(outcome is HandoffOutcome.Running)
            val upstream = tunnel.lastUpstream
            assertTrue(upstream is TunnelUpstream.Xray)
            upstream as TunnelUpstream.Xray
            assertEquals("127.0.0.1", upstream.host)
            assertEquals(10808, upstream.port)
            assertEquals(VpnProviderState.Running, orch.xrayState)
        }

    @Test
    fun `protect is registered before any outbound and before tunnel start`() =
        runTest {
            val events = mutableListOf<String>()
            val tunnel =
                object : FakeManagedTunnel() {
                    override suspend fun start(upstream: TunnelUpstream) {
                        events += "tunnel.start"
                        super.start(upstream)
                    }
                }
            // Bridge that records registerProtect + start into the shared event log.
            val recordingBridge =
                object : FakeXrayNativeBridge() {
                    override fun registerProtect(controller: XrayProtectController) {
                        events += "registerProtect"
                        super.registerProtect(controller)
                    }

                    override fun start(jsonConfig: String): Int {
                        events += "xray.start"
                        return super.start(jsonConfig)
                    }
                }
            val orch =
                XrayProviderOrchestrator(
                    xrayRuntimeFactory = { cfg ->
                        RipDpiXrayRuntime(
                            XrayRuntimeOwner(recordingBridge, kotlinx.coroutines.Dispatchers.Unconfined),
                            cfg,
                        )
                    },
                    tunnel = tunnel,
                    protectController = RecordingProtect(events),
                    renderedConfigProvider = { renderedConfig },
                )

            orch.start(ProviderRoute(VpnProviderKind.Xray))

            // registerProtect must strictly precede xray.start (protect-first),
            // and xray.start must precede tunnel.start (inbound-ready-first).
            val protectIdx = events.indexOf("registerProtect")
            val xrayStartIdx = events.indexOf("xray.start")
            val tunnelStartIdx = events.indexOf("tunnel.start")
            assertTrue("registerProtect logged", protectIdx >= 0)
            assertTrue("xray.start logged", xrayStartIdx >= 0)
            assertTrue("tunnel.start logged", tunnelStartIdx >= 0)
            assertTrue("protect before xray outbound", protectIdx < xrayStartIdx)
            assertTrue("xray inbound ready before tunnel start", xrayStartIdx < tunnelStartIdx)
        }

    @Test
    fun `dns is resolved by the tunnel and never re-enters via xray`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray))

            val upstream = tunnel.lastUpstream as TunnelUpstream.Xray
            // Single source of truth: tunnel owns DNS; Xray's DNS outbound is not
            // used for the tunnelled path, so DNS cannot loop back into the TUN.
            assertEquals(DnsLoopOwner.Tunnel, upstream.dnsOwner)
        }

    @Test
    fun `handover on inbound port change restarts both xray and tunnel`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808)))
            val startsBefore = bridge.startCount
            val tunnelStartsBefore = tunnel.startCount

            val outcome =
                orch.handover(
                    ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 20808)),
                )

            assertTrue(outcome is HandoffOutcome.Running)
            // BOTH restarted: xray stopped + restarted, tunnel stopped + restarted.
            assertEquals(1, bridge.stopCount)
            assertEquals(startsBefore + 1, bridge.startCount)
            assertTrue(tunnel.quiesceCount >= 1)
            assertEquals(0, tunnel.stopCount)
            assertEquals(tunnelStartsBefore + 1, tunnel.startCount)
            // Tunnel now points at the new inbound port.
            assertEquals(20808, (tunnel.lastUpstream as TunnelUpstream.Xray).port)
        }

    @Test
    fun `handover start failure retains route ownership and TUN barrier for retry`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)
            val firstRoute = ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808))
            val replacementRoute = ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 20808))

            assertTrue(orch.start(firstRoute) is HandoffOutcome.Running)
            tunnel.failOnStart = true

            val failed = orch.handover(replacementRoute)

            assertTrue(failed is HandoffOutcome.Failed)
            assertEquals(replacementRoute, orch.currentRoute)
            assertFalse(tunnel.isRunning)
            assertEquals(0, tunnel.stopCount)

            tunnel.failOnStart = false
            val retried = orch.handover(replacementRoute)

            assertTrue(retried is HandoffOutcome.Running)
            assertEquals(replacementRoute, orch.currentRoute)
            assertEquals(0, tunnel.stopCount)
            assertEquals(20808, (tunnel.lastUpstream as TunnelUpstream.Xray).port)
        }

    @Test
    fun `handover quiesces forwarding before stopping xray without releasing TUN`() =
        runTest {
            val events = mutableListOf<String>()
            val bridge =
                object : FakeXrayNativeBridge() {
                    override fun stop() {
                        events += "xray.stop"
                        super.stop()
                    }
                }
            val tunnel =
                object : FakeManagedTunnel() {
                    override suspend fun quiesce() {
                        events += "tunnel.quiesce"
                        super.quiesce()
                    }
                }
            val orch =
                XrayProviderOrchestrator(
                    xrayRuntimeFactory = { cfg ->
                        RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined), cfg)
                    },
                    tunnel = tunnel,
                    protectController = XrayProtectController { true },
                    renderedConfigProvider = { renderedConfig },
                )

            orch.start(ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808)))
            events.clear()
            orch.handover(ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 20808)))

            val tunnelStopIdx = events.indexOf("tunnel.quiesce")
            val xrayStopIdx = events.indexOf("xray.stop")
            assertTrue("tunnel.quiesce logged", tunnelStopIdx >= 0)
            assertTrue("xray.stop logged", xrayStopIdx >= 0)
            assertTrue("tunnel stops before xray", tunnelStopIdx < xrayStopIdx)
        }

    @Test
    fun `handover with equivalent route is a no-op preserving the session`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)
            val route = ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808))

            orch.start(route)
            val startsBefore = bridge.startCount
            val tunnelStartsBefore = tunnel.startCount

            val outcome = orch.handover(route)

            assertTrue(outcome is HandoffOutcome.Running)
            // No restart: counts unchanged.
            assertEquals(startsBefore, bridge.startCount)
            assertEquals(tunnelStartsBefore, tunnel.startCount)
            assertEquals(0, bridge.stopCount)
        }

    @Test
    fun `handover from xray to native tears down xray and switches upstream`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray))
            orch.handover(ProviderRoute(VpnProviderKind.Native))

            assertEquals(1, bridge.stopCount)
            assertEquals(VpnProviderState.Stopped, orch.xrayState)
            assertEquals(TunnelUpstream.Native, tunnel.lastUpstream)
        }

    @Test
    fun `tunnel start failure on xray path tears xray back down`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel(failOnStart = true)
            val orch = orchestrator(bridge, tunnel)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Xray))

            assertTrue(outcome is HandoffOutcome.Failed)
            // Xray was started then stopped; no half session is left.
            assertEquals(1, bridge.startCount)
            assertEquals(1, bridge.stopCount)
            assertNull(orch.currentRoute)
        }

    @Test
    fun `xray start failure surfaces and starts no tunnel`() =
        runTest {
            val bridge = FakeXrayNativeBridge(startCode = 7)
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Xray))

            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, tunnel.startCount)
            assertFalse(tunnel.isRunning)
            assertNull(orch.currentRoute)
        }

    @Test
    fun `start twice without stop is rejected`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Native))
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { orch.start(ProviderRoute(VpnProviderKind.Native)) }
            }
        }

    @Test
    fun `stop tears down tunnel and xray and is idempotent`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray))
            assertEquals(HandoffOutcome.Stopped, orch.stop())
            assertFalse(tunnel.isRunning)
            assertEquals(VpnProviderState.Stopped, orch.xrayState)
            // Second stop is a clean no-op.
            assertEquals(HandoffOutcome.Stopped, orch.stop())
        }

    @Test
    fun `set-tun-fd route fails fast without starting anything`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)
            val route =
                ProviderRoute(
                    VpnProviderKind.Xray,
                    XrayProviderConfig(tunnelTopology = com.poyka.ripdpi.data.xray.XrayTunnelTopology.LibXraySetTunFd),
                )

            val outcome = orch.start(route)

            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, bridge.startCount)
            assertEquals(0, tunnel.startCount)
        }
}
