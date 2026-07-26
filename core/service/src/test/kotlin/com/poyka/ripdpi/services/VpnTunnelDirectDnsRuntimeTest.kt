package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelDirectDnsRuntimeTest {
    private companion object {
        const val TestLocalProxyAuth = "alpha-123"
    }

    @Test
    fun directDnsLeaseIsPreparedBeforeEstablishAndCommittedBeforeNativeStart() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope, events).apply { directDnsApplyResult = false }
            val bridge = TestTun2SocksBridge(events)
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            val policy =
                ValidatedSplitStrictDnsPolicy.build(
                    activeDns = activeDns,
                    routingSnapshot =
                        DestinationRoutingPolicySnapshot.Available(
                            DestinationRoutingPolicy(canonicalDigest = "empty-test-policy"),
                        ),
                    underlayDnsServers = listOf("192.0.2.53", "2001:db8::53"),
                    underlayLeaseGeneration = 23L,
                )
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = TestVpnTunnelSession(events = events),
                        ),
                )

            runtime.start(
                activeDns = activeDns,
                overrideReason = null,
                logContext = null,
                localProxyEndpoint =
                    LocalProxyEndpoint(
                        host = "127.0.0.1",
                        port = 18080,
                        username = VpnLocalProxyUsername,
                        password = TestLocalProxyAuth,
                    ),
                splitStrictDnsPolicy = policy,
            )

            assertEquals(listOf("192.0.2.53", "2001:db8:0:0:0:0:0:53"), host.lastDirectDnsCandidates)
            assertEquals(23L, host.lastDirectDnsNetworkHandle)
            assertEquals(1, host.directDnsApplyCount)
            assertEquals(listOf("dns:prepare", "vpn:establish", "dns:commit:1", "tunnel:start"), events)
            assertTrue("publish rejection must continue with encrypted DNS fallback", runtime.isForwarding)
        }

    @Test
    fun directDnsLeaseRollsBackWhenNativeStartFails() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope, events).apply { directDnsPrepareToken = 41L }
            val bridge =
                TestTun2SocksBridge(events).apply {
                    startFailure = IllegalStateException("native start failed")
                }
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            val policy =
                ValidatedSplitStrictDnsPolicy.build(
                    activeDns = activeDns,
                    routingSnapshot =
                        DestinationRoutingPolicySnapshot.Available(
                            DestinationRoutingPolicy(canonicalDigest = "empty-test-policy"),
                        ),
                    underlayDnsServers = listOf("192.0.2.53"),
                    underlayLeaseGeneration = 23L,
                )
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = TestVpnTunnelSession(events = events),
                        ),
                )

            val failure =
                runCatching {
                    runtime.start(
                        activeDns = activeDns,
                        overrideReason = null,
                        logContext = null,
                        localProxyEndpoint =
                            LocalProxyEndpoint(
                                host = "127.0.0.1",
                                port = 18080,
                                username = VpnLocalProxyUsername,
                                password = TestLocalProxyAuth,
                            ),
                        splitStrictDnsPolicy = policy,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(
                listOf("dns:prepare", "vpn:establish", "dns:commit:41", "tunnel:start", "dns:failclosed:41"),
                events,
            )
        }

    @Test
    fun replacementLeaseCommitsOnlyAfterOldRuntimeStops() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope, events).apply { directDnsPrepareToken = 1L }
            val bridge = TestTun2SocksBridge(events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = TestVpnTunnelSession(tunFd = 7, events = events),
                )
            val runtime = directDnsRuntime(host, bridge, sessionProvider)
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            val policy = directDnsPolicy(activeDns)
            runtime.start(activeDns, null, null, localProxyEndpoint(), splitStrictDnsPolicy = policy)
            host.directDnsPrepareToken = 2L
            sessionProvider.session = TestVpnTunnelSession(tunFd = 8, events = events)

            runtime.rebuild(activeDns, null, null, localProxyEndpoint(), splitStrictDnsPolicy = policy)

            assertEquals(
                listOf(
                    "dns:prepare",
                    "vpn:establish",
                    "dns:commit:1",
                    "tunnel:start",
                    "dns:prepare",
                    "vpn:establish",
                    "tunnel:stop",
                    "vpn:session-close",
                    "dns:commit:2",
                    "tunnel:start",
                ),
                events,
            )
        }

    @Test
    fun replacementNativeStartFailureFailClosesReplacementAfterOldRuntimeStops() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope, events).apply { directDnsPrepareToken = 1L }
            val firstBridge = TestTun2SocksBridge(events)
            val failingBridge =
                TestTun2SocksBridge(events).apply {
                    startFailure = IllegalStateException("replacement native start failed")
                }
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = TestVpnTunnelSession(tunFd = 7, events = events),
                )
            val runtime =
                directDnsRuntime(
                    host = host,
                    bridgeFactory = TestTun2SocksBridgeFactory(firstBridge, listOf(failingBridge)),
                    sessionProvider = sessionProvider,
                )
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            val policy = directDnsPolicy(activeDns)
            runtime.start(activeDns, null, null, localProxyEndpoint(), splitStrictDnsPolicy = policy)
            host.directDnsPrepareToken = 2L
            sessionProvider.session = TestVpnTunnelSession(tunFd = 8, events = events)

            val failure =
                runCatching {
                    runtime.rebuild(activeDns, null, null, localProxyEndpoint(), splitStrictDnsPolicy = policy)
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(
                listOf(
                    "dns:prepare",
                    "vpn:establish",
                    "dns:commit:1",
                    "tunnel:start",
                    "dns:prepare",
                    "vpn:establish",
                    "tunnel:stop",
                    "vpn:session-close",
                    "dns:commit:2",
                    "tunnel:start",
                    "dns:failclosed:2",
                ),
                events,
            )
            assertTrue("replacement TUN remains installed as a blocking session", runtime.isRunning)
            assertFalse("failed native start must not report forwarding", runtime.isForwarding)
            assertEquals("fail-closed clears the JNI-visible lease generation", 0L, host.directDnsLeaseGeneration)
        }

    @Test
    fun replacementEstablishFailureAbortsBAndNeverPublishesItToActiveA() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope, events).apply { directDnsPrepareToken = 1L }
            val bridge = TestTun2SocksBridge(events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = TestVpnTunnelSession(tunFd = 7, events = events),
                )
            val runtime = directDnsRuntime(host, bridge, sessionProvider)
            val activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings()
            val policy = directDnsPolicy(activeDns)
            runtime.start(activeDns, null, null, localProxyEndpoint(), splitStrictDnsPolicy = policy)
            host.directDnsPrepareToken = 2L
            sessionProvider.establishFailure = IllegalStateException("replacement establish failed")

            val failure =
                runCatching {
                    runtime.rebuild(activeDns, null, null, localProxyEndpoint(), splitStrictDnsPolicy = policy)
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(
                listOf(
                    "dns:prepare",
                    "vpn:establish",
                    "dns:commit:1",
                    "tunnel:start",
                    "dns:prepare",
                    "vpn:establish",
                    "dns:abort:2",
                ),
                events,
            )
            assertTrue(runtime.isForwarding)
        }

    private fun directDnsRuntime(
        host: TestVpnServiceHost,
        bridge: TestTun2SocksBridge,
        sessionProvider: TestVpnTunnelSessionProvider,
    ): VpnTunnelRuntime =
        directDnsRuntime(
            host = host,
            bridgeFactory = TestTun2SocksBridgeFactory(bridge),
            sessionProvider = sessionProvider,
        )

    private fun directDnsRuntime(
        host: TestVpnServiceHost,
        bridgeFactory: Tun2SocksBridgeFactory,
        sessionProvider: TestVpnTunnelSessionProvider,
    ): VpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = host,
            appSettingsRepository = TestAppSettingsRepository(),
            proxyGroupRepository = TestProxyGroupRepository(),
            tun2SocksBridgeFactory = bridgeFactory,
            vpnTunnelSessionProvider = sessionProvider,
        )

    private fun directDnsPolicy(activeDns: com.poyka.ripdpi.data.ActiveDnsSettings): ValidatedSplitStrictDnsPolicy =
        ValidatedSplitStrictDnsPolicy.build(
            activeDns = activeDns,
            routingSnapshot =
                DestinationRoutingPolicySnapshot.Available(
                    DestinationRoutingPolicy(canonicalDigest = "empty-test-policy"),
                ),
            underlayDnsServers = listOf("192.0.2.53"),
            underlayLeaseGeneration = 23L,
        )

    private fun localProxyEndpoint(): LocalProxyEndpoint =
        LocalProxyEndpoint(
            host = "127.0.0.1",
            port = 18080,
            username = VpnLocalProxyUsername,
            password = TestLocalProxyAuth,
        )
}
