package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnRuntimeCompositionCoordinatorTest {
    @Test
    @Suppress("LongMethod")
    fun `native start preserves proxy ready snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val readySnapshot =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    listenerAddress = "127.0.0.1:18082",
                    autolearnEnabled = true,
                    capturedAt = 1787231291789069L,
                )
            val upstreamRelaySupervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore = TestRelayProfileStore(),
                    relayCredentialStore = TestRelayCredentialStore(),
                )
            val warpRuntimeSupervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = TestRipDpiWarpFactory(),
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )
            val awgRuntimeSupervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                    runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                )
            val proxyRuntimeSupervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory =
                        TestRipDpiProxyFactory {
                            TestProxyRuntime().apply { telemetry = readySnapshot }
                        },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val runtimeStack =
                SharedProxyRuntimeStack(
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    amneziaWgRuntimeSupervisor = awgRuntimeSupervisor,
                    proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                )
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope)
            val tunnelRuntime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = TestVpnTunnelSession(events = events),
                        ),
                )
            val exitHandler =
                VpnSupervisorExitHandler(
                    host = host,
                    ioDispatcher = dispatcher,
                    proxyRuntimeStack = runtimeStack,
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    amneziaWgRuntimeSupervisor = awgRuntimeSupervisor,
                    updateStatus = { _, _ -> },
                    markForeignRelayFailed = {},
                    stopService = {},
                )
            val coordinator =
                VpnRuntimeCompositionCoordinator(
                    proxyRuntimeStack = runtimeStack,
                    vpnTunnelRuntime = tunnelRuntime,
                    supervisorExitHandler = exitHandler,
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )

            val session = VpnRuntimeSession("runtime-test")
            val result = coordinator.start(session, sampleResolution(mode = Mode.VPN))

            assertTrue(result != null)
            assertSame(readySnapshot, result?.readySnapshot)
            val routeLease = session.diagnosticsInPathRouteLease
            assertNotNull(routeLease)
            assertEquals(result?.endpoint?.host, routeLease?.host)
            assertEquals(result?.endpoint?.port, routeLease?.port)
            assertEquals(result?.endpoint?.username, routeLease?.credentials?.username)
            assertEquals(result?.endpoint?.password, routeLease?.credentials?.password)
            coordinator.stop(skipRuntimeShutdown = false)
        }
}
