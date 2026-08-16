package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelRuntimeRecoveryTest {
    private val localProxyEndpoint = VpnTunnelRuntimeTest.localProxyEndpoint

    @Test
    fun `successful bridge start records the TUN milestone once`() =
        runTest {
            var tunnelReadyCount = 0
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge()),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(),
                    callbacks = VpnTunnelRuntimeCallbacks(onTunnelReady = { tunnelReadyCount += 1 }),
                )

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(1, tunnelReadyCount)
        }

    @Test
    fun tunnelStartFailureRetainsEstablishedSessionUntilOrchestratedStop() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events).apply { startFailure = IllegalStateException("boom") }
            val session = TestVpnTunnelSession(events = events)
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = session,
                        ),
                )

            val failure =
                runCatching {
                    runtime.start(
                        AppSettingsSerializer.defaultValue.activeDnsSettings(),
                        overrideReason = null,
                        logContext = null,
                        localProxyEndpoint = localProxyEndpoint,
                    )
                }

            assertTrue(failure.isFailure)
            assertFalse(session.closed)
            assertFalse(runtime.isRunning)

            runtime.stop()

            assertTrue(session.closed)
        }

    @Test
    fun rebuildEstablishFailureKeepsExistingTunnelRunning() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val originalSession = TestVpnTunnelSession(events = events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = originalSession,
                )
            val receiptStore = VpnTunnelAppliedNetworkReceiptStore()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                    appliedNetworkReceiptStore = receiptStore,
                )
            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val originalReceipt = receiptStore.snapshot()
            sessionProvider.establishFailure = IllegalStateException("replacement establish failed")

            val failure =
                runCatching {
                    runtime.rebuild(
                        AppSettingsSerializer.defaultValue.activeDnsSettings(),
                        overrideReason = null,
                        logContext = null,
                        localProxyEndpoint = localProxyEndpoint,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(originalSession.closed)
            assertTrue(runtime.isRunning)
            assertTrue(runtime.isForwarding)
            assertEquals(0, bridge.stopCount)
            assertSame(originalReceipt, receiptStore.snapshot())
        }

    @Test
    fun rebuildStartFailureKeepsReplacementTunOpenAsFailClosedBarrier() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val originalSession = TestVpnTunnelSession(tunFd = 7, events = events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = originalSession,
                )
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                )
            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val replacementSession = TestVpnTunnelSession(tunFd = 8, events = events)
            sessionProvider.session = replacementSession
            bridge.startFailure = IllegalStateException("replacement bridge failed")

            val failure =
                runCatching {
                    runtime.rebuild(
                        AppSettingsSerializer.defaultValue.activeDnsSettings(),
                        overrideReason = null,
                        logContext = null,
                        localProxyEndpoint = localProxyEndpoint,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(originalSession.closed)
            assertFalse(replacementSession.closed)
            assertTrue(runtime.isRunning)
            assertFalse(runtime.isForwarding)
            assertEquals(
                listOf(
                    "vpn:establish",
                    "tunnel:start",
                    "vpn:establish",
                    "tunnel:stop",
                    "vpn:session-close",
                    "tunnel:start",
                ),
                events,
            )
        }

    @Test
    fun rebuildRetryRecoversFromRetainedFailClosedTunBarrier() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val originalSession = TestVpnTunnelSession(tunFd = 7, events = events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = originalSession,
                )
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                )
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            runtime.start(activeDns, null, null, localProxyEndpoint)

            val failedReplacement = TestVpnTunnelSession(tunFd = 8, events = events)
            sessionProvider.session = failedReplacement
            bridge.startFailure = IllegalStateException("replacement bridge failed")
            assertTrue(
                runCatching {
                    runtime.rebuild(activeDns, null, null, localProxyEndpoint)
                }.isFailure,
            )

            val recoveredReplacement = TestVpnTunnelSession(tunFd = 9, events = events)
            sessionProvider.session = recoveredReplacement
            bridge.startFailure = null
            runtime.rebuild(activeDns, null, null, localProxyEndpoint)

            assertTrue(originalSession.closed)
            assertTrue(failedReplacement.closed)
            assertFalse(recoveredReplacement.closed)
            assertTrue(runtime.isRunning)
            assertTrue(runtime.isForwarding)
            assertEquals(3, bridge.startedConfigs.size)
            assertEquals(1, bridge.stopCount)
        }

    @Test
    fun rebuildRetryCannotOverwriteAStillUnclosedRetiringSession() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val originalSession = TestVpnTunnelSession(events = events)
            val sessionProvider = TestVpnTunnelSessionProvider(events = events, session = originalSession)
            val receiptStore = VpnRouteLifecycleReceiptStore()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                    routeLifecycleReceiptStore = receiptStore,
                )
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            runtime.start(activeDns, null, null, localProxyEndpoint)
            val replacementSession = TestVpnTunnelSession(tunFd = 8, events = events)
            sessionProvider.session = replacementSession
            originalSession.beforeClose = { throw java.io.IOException("old descriptor close failed") }

            assertTrue(runCatching { runtime.rebuild(activeDns, null, null, localProxyEndpoint) }.isFailure)
            assertTrue(runtime.isRunning)
            assertFalse(originalSession.closed)
            assertFalse(replacementSession.closed)
            assertEquals(2, events.count { it == "vpn:establish" })

            val recoveredSession = TestVpnTunnelSession(tunFd = 9, events = events)
            sessionProvider.session = recoveredSession
            assertTrue(runCatching { runtime.rebuild(activeDns, null, null, localProxyEndpoint) }.isFailure)
            assertEquals(2, events.count { it == "vpn:establish" })
            assertFalse(originalSession.closed)
            assertFalse(recoveredSession.closed)

            originalSession.beforeClose = null
            runtime.rebuild(activeDns, null, null, localProxyEndpoint)

            assertTrue(originalSession.closed)
            assertTrue(replacementSession.closed)
            assertFalse(recoveredSession.closed)
            assertEquals(3, events.count { it == "vpn:establish" })
            assertTrue(runtime.isRunning)
            assertTrue(runtime.isForwarding)

            runtime.stop()
            assertTrue(recoveredSession.closed)
            assertEquals(VpnRouteLifecycleState.Closed, receiptStore.lifecycleReceipt?.state)
        }

    @Test
    fun retainFailClosedBarrierStopsForwardingWithoutClosingTun() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val session = TestVpnTunnelSession(events = events)
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(events = events, session = session),
                )
            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), null, null, localProxyEndpoint)

            assertTrue(runtime.retainFailClosedBarrier())

            assertTrue(runtime.isRunning)
            assertFalse(runtime.isForwarding)
            assertFalse(session.closed)
            assertEquals(1, bridge.stopCount)
        }

    @Test
    fun rebuildStopFailureRetainsBridgeForCleanupAndReplacementTunBarrier() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val originalSession = TestVpnTunnelSession(tunFd = 7, events = events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = originalSession,
                )
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                )
            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val replacementSession = TestVpnTunnelSession(tunFd = 8, events = events)
            sessionProvider.session = replacementSession
            bridge.stopFailure = IllegalStateException("old bridge stop failed")

            val failure =
                runCatching {
                    runtime.rebuild(
                        AppSettingsSerializer.defaultValue.activeDnsSettings(),
                        overrideReason = null,
                        logContext = null,
                        localProxyEndpoint = localProxyEndpoint,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(originalSession.closed)
            assertFalse(replacementSession.closed)
            assertTrue(runtime.isRunning)
            assertFalse(runtime.isForwarding)
            bridge.stopFailure = null

            runtime.stop()

            assertEquals(2, bridge.stopCount)
            assertTrue(replacementSession.closed)
            assertFalse(runtime.isRunning)
        }
}
