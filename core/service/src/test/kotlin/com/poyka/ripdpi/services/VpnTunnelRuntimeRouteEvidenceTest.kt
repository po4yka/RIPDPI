package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VpnTunnelRuntimeRouteEvidenceTest {
    @Test
    fun `route lifecycle receipt is established before native bridge readiness`() =
        runTest {
            val routeReceiptStore = VpnRouteLifecycleReceiptStore()
            val bridge =
                TestTun2SocksBridge().apply {
                    beforeStart = {
                        val receipt = checkNotNull(routeReceiptStore.lifecycleReceipt)
                        assertEquals(VpnRouteLifecycleState.Established, receipt.state)
                        assertEquals(listOf("ipv4"), receipt.intendedDefaultRouteFamilies)
                    }
                }
            val runtime =
                createRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    bridge = bridge,
                    receiptStore = routeReceiptStore,
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = VpnTunnelRuntimeTest.localProxyEndpoint,
            )

            val receipt = routeReceiptStore.lifecycleReceipt
            assertEquals(VpnRouteLifecycleState.BridgeReady, receipt?.state)
            assertEquals(1L, receipt?.appliedTunnelReceiptGeneration)
            assertEquals("unavailable", routeReceiptStore.capture().forwardingOutcome)
        }

    @Test
    fun `bridge factory failure retains established tunnel as fail closed`() =
        runTest {
            val routeReceiptStore = VpnRouteLifecycleReceiptStore()
            val runtime =
                createRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    bridgeFactory =
                        object : Tun2SocksBridgeFactory {
                            override fun create(): Tun2SocksBridge = throw IOException("bridge create failed")
                        },
                    receiptStore = routeReceiptStore,
                )

            val failure = runCatching { runtime.startTunnel() }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(VpnRouteLifecycleState.FailClosed, routeReceiptStore.lifecycleReceipt?.state)
            assertEquals("fail_closed", routeReceiptStore.capture().forwardingOutcome)
        }

    @Test
    fun `failure after native start rolls forwarding back before fail closed receipt`() =
        runTest {
            val routeReceiptStore = VpnRouteLifecycleReceiptStore()
            val bridge = TestTun2SocksBridge()
            val runtime =
                createRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    bridge = bridge,
                    receiptStore = routeReceiptStore,
                    callbacks = VpnTunnelRuntimeCallbacks(onTunnelReady = { throw IOException("publish failed") }),
                )

            val failure = runCatching { runtime.startTunnel() }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(1, bridge.stopCount)
            assertFalse(runtime.isForwarding)
            assertNull(runtime.pollForwardingEvidence())
            assertEquals(VpnRouteLifecycleState.FailClosed, routeReceiptStore.lifecycleReceipt?.state)
        }

    @Test
    fun `descriptor close failure retains retry ownership without claiming closed`() =
        runTest {
            val routeReceiptStore = VpnRouteLifecycleReceiptStore()
            val session = TestVpnTunnelSession()
            val runtime =
                createRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    session = session,
                    receiptStore = routeReceiptStore,
                )
            runtime.startTunnel()
            session.beforeClose = { throw IOException("descriptor close failed") }

            val failure = runCatching { runtime.stop() }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(runtime.isRunning)
            assertEquals(VpnRouteLifecycleState.FailClosed, routeReceiptStore.lifecycleReceipt?.state)
            session.beforeClose = null
            runtime.stop()
            assertFalse(runtime.isRunning)
            assertEquals(VpnRouteLifecycleState.Closed, routeReceiptStore.lifecycleReceipt?.state)
        }

    private suspend fun VpnTunnelRuntime.startTunnel() {
        start(
            activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
            overrideReason = null,
            logContext = null,
            localProxyEndpoint = VpnTunnelRuntimeTest.localProxyEndpoint,
        )
    }

    private fun createRuntime(
        vpnHost: VpnCoordinatorHost,
        bridge: TestTun2SocksBridge = TestTun2SocksBridge(),
        bridgeFactory: Tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
        session: TestVpnTunnelSession = TestVpnTunnelSession(),
        receiptStore: VpnRouteLifecycleReceiptStore,
        callbacks: VpnTunnelRuntimeCallbacks = VpnTunnelRuntimeCallbacks(),
    ): VpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = vpnHost,
            appSettingsRepository = TestAppSettingsRepository(),
            proxyGroupRepository = TestProxyGroupRepository(),
            tun2SocksBridgeFactory = bridgeFactory,
            vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = session),
            callbacks = callbacks,
            routeLifecycleReceiptStore = receiptStore,
        )
}
