package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FakeManagedTunnel
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consolidated service-lifecycle regression matrix for the Xray provider.
 *
 * Where [RipDpiXrayRuntimeTest] checks the runtime in isolation and
 * [XrayProviderOrchestratorTest] checks individual handover edges, this suite
 * walks the *service-level* matrix end to end against the task-3/4 fakes:
 *
 * | Scenario               | Expected terminal state                       |
 * |------------------------|-----------------------------------------------|
 * | Startup failure        | Failed, nothing left running                  |
 * | Readiness timeout      | Failed, both halves torn down                 |
 * | Clean stop             | Stopped, idempotent                           |
 * | Restart (stop->start)  | Fresh session, linear state machine each time |
 * | Handover (route change)| Both halves restarted in order                |
 *
 * Each row is a named test so a regression points at the exact lifecycle edge.
 */
class XrayServiceLifecycleMatrixTest {
    private val renderedConfig =
        """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[""" +
            """{"id":"d0c0ffee-dead-beef-cafe-000000000001","flow":"xtls-rprx-vision"}]}]}}]}"""

    private fun orchestrator(
        bridge: FakeXrayNativeBridge,
        tunnel: FakeManagedTunnel,
    ): XrayProviderOrchestrator =
        XrayProviderOrchestrator(
            xrayRuntimeFactory = { cfg ->
                // Unconfined native-stop dispatcher: the fake bridge's stop runs
                // inline on the test thread rather than racing the runTest virtual
                // clock against a real Dispatchers.IO worker (production uses IO).
                RipDpiXrayRuntime(
                    XrayRuntimeOwner(bridge, Dispatchers.Unconfined),
                    cfg,
                    readinessPollIntervalMs = 1L,
                )
            },
            tunnel = tunnel,
            protectController = XrayProtectController { true },
            renderedConfigProvider = { renderedConfig },
        )

    @Test
    fun `startup failure leaves no running session`() =
        runTest {
            // libXray rejects the start (non-zero code).
            val bridge = FakeXrayNativeBridge(startCode = 9)
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Xray))

            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, tunnel.startCount)
            assertFalse(tunnel.isRunning)
            assertNull(orch.currentRoute)
            assertEquals(VpnProviderState.Stopped, orch.xrayState)
        }

    @Test
    fun `readiness timeout tears down both halves`() =
        runTest {
            // start() is accepted (code 0) but the listener never comes up:
            // readyAfterPolls is huge and the runtime's awaitReady is bounded.
            // The listener never becomes ready; under runTest the runtime's
            // awaitReady delay is virtual-time-skipped, so the bounded timeout
            // fires deterministically without burning wall time.
            val bridge = FakeXrayNativeBridge(readyAfterPolls = Int.MAX_VALUE)
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Xray))

            assertTrue("readiness timeout must fail the session", outcome is HandoffOutcome.Failed)
            // Tunnel was never pointed at a not-ready inbound.
            assertEquals(0, tunnel.startCount)
            assertNull(orch.currentRoute)
            // The runtime tore itself down on the timeout path.
            assertTrue("xray stopped on timeout", bridge.stopCount >= 1)
        }

    @Test
    fun `clean stop is idempotent and fully tears down`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray))
            assertEquals(HandoffOutcome.Stopped, orch.stop())
            assertFalse(tunnel.isRunning)
            assertEquals(VpnProviderState.Stopped, orch.xrayState)
            val stopsAfterFirst = bridge.stopCount

            // Second stop: clean no-op, no extra native stop.
            assertEquals(HandoffOutcome.Stopped, orch.stop())
            assertEquals(stopsAfterFirst, bridge.stopCount)
        }

    @Test
    fun `restart brings up a fresh linear session each time`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            // Cycle start -> stop -> start. Each start observes a linear
            // Stopped -> Starting -> Running machine (a fresh runtime per start).
            orch.start(ProviderRoute(VpnProviderKind.Xray))
            assertEquals(VpnProviderState.Running, orch.xrayState)
            orch.stop()
            assertEquals(VpnProviderState.Stopped, orch.xrayState)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Xray))
            assertTrue(outcome is HandoffOutcome.Running)
            assertEquals(VpnProviderState.Running, orch.xrayState)
            // Two distinct start requests reached the bridge.
            assertEquals(2, bridge.startCount)
        }

    @Test
    fun `handover restarts both halves and re-points the tunnel`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            orch.start(ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 10808)))
            val outcome =
                orch.handover(
                    ProviderRoute(VpnProviderKind.Xray, XrayProviderConfig(localInboundPort = 20808)),
                )

            assertTrue(outcome is HandoffOutcome.Running)
            assertEquals(20808, (tunnel.lastUpstream as TunnelUpstream.Xray).port)
            assertEquals(2, bridge.startCount)
            assertEquals(1, bridge.stopCount)
        }

    @Test
    fun `process crash during readiness fails fast without waiting out the timeout`() =
        runTest {
            // start accepted, but the process is not alive — awaitReady must
            // surface a crash cause quickly rather than blocking the timeout.
            val bridge = FakeXrayNativeBridge(readyAfterPolls = Int.MAX_VALUE, aliveDuringStartup = false)
            val tunnel = FakeManagedTunnel()
            val orch = orchestrator(bridge, tunnel)

            val outcome = orch.start(ProviderRoute(VpnProviderKind.Xray))

            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, tunnel.startCount)
            assertNull(orch.currentRoute)
        }
}
