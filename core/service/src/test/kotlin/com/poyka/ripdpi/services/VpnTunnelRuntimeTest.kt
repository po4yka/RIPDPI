package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VpnTunnelRuntimeTest {
    private companion object {
        const val TestLocalProxyAuth = "alpha-123"

        val localProxyEndpoint =
            LocalProxyEndpoint(
                host = "127.0.0.1",
                port = 18080,
                username = VpnLocalProxyUsername,
                password = TestLocalProxyAuth,
            )
    }

    @Test
    fun successfulStartStoresDnsSignatureAndSyncsHost() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope)
            val runtime =
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

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertTrue(runtime.isRunning)
            assertEquals(
                dnsSignature(AppSettingsSerializer.defaultValue.activeDnsSettings(), null),
                runtime.currentDnsSignature,
            )
            assertEquals(0L, runtime.tunnelRecoveryRetryCount)
            assertEquals(1, host.underlyingNetworkSyncs)
        }

    @Test
    fun startUsesOneAppRoutingPlanForAndroidAndNativePolicies() =
        runTest {
            val expectedPlan = VpnAppRoutingPlan.AllowOnly(setOf("com.example.allowed"))
            val expectedUidPolicy = NativeUidPolicy("allowlist", listOf(10_321))
            val host = TestVpnServiceHost(backgroundScope).apply { appRoutingPlan = expectedPlan }
            val sessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession())
            val bridge = TestTun2SocksBridge()
            var nativePlan: VpnAppRoutingPlan? = null
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                    nativeUidPolicyProvider = { plan ->
                        nativePlan = plan
                        expectedUidPolicy
                    },
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertSame(expectedPlan, nativePlan)
            assertSame(expectedPlan, sessionProvider.lastAppRoutingPlan)
            assertEquals(expectedUidPolicy.mode, bridge.startedConfig?.uidPolicyMode)
            assertEquals(expectedUidPolicy.uids, bridge.startedConfig?.uidPolicyUids)
        }

    @Test
    fun rebuildAdvancesAndroidAndNativePoliciesToTheSamePlanGeneration() =
        runTest {
            val initialPlan = VpnAppRoutingPlan.AllowOnly(setOf("com.example.initial"))
            val replacementPlan = VpnAppRoutingPlan.Disallow(setOf("com.example.replacement"))
            val host = TestVpnServiceHost(backgroundScope).apply { appRoutingPlan = initialPlan }
            val sessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession(tunFd = 7))
            val bridge = TestTun2SocksBridge()
            val nativePlans = mutableListOf<VpnAppRoutingPlan>()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                    nativeUidPolicyProvider = { plan ->
                        nativePlans += plan
                        when (plan) {
                            is VpnAppRoutingPlan.AllowOnly -> NativeUidPolicy("allowlist", listOf(10_321))
                            is VpnAppRoutingPlan.Disallow -> NativeUidPolicy("denylist", listOf(10_322))
                        }
                    },
                )
            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            host.appRoutingPlan = replacementPlan
            sessionProvider.session = TestVpnTunnelSession(tunFd = 8)

            runtime.rebuild(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(listOf(initialPlan, replacementPlan), nativePlans)
            assertSame(replacementPlan, sessionProvider.lastAppRoutingPlan)
            assertEquals("denylist", bridge.startedConfig?.uidPolicyMode)
            assertEquals(listOf(10_322), bridge.startedConfig?.uidPolicyUids)
        }

    @Test
    fun startPassesAdaptiveTunnelMtuToNativeConfig() =
        runTest {
            val host =
                TestVpnServiceHost(backgroundScope).apply {
                    tunnelNetworkParameters = VpnTunnelNetworkParameters(tunnelMtu = 1_320, metered = true)
                }
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(1_320, bridge.startedConfig?.tunnelMtu)
        }

    @Test
    fun startPassesWebRtcProtectionToNativeConfig() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setWebrtcProtectionEnabled(true)
                    .build()
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                activeDns = settings.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertTrue(bridge.startedConfig?.webrtcProtectionEnabled == true)
        }

    @Test
    fun secondStartIncrementsRecoveryRetryCount() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope)
            val runtime =
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

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            runtime.stop()
            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(1L, runtime.tunnelRecoveryRetryCount)
        }

    @Test
    fun rootModeAddsRootHelperSocketToTunnelConfig() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events)
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRootModeEnabled(true)
                    .build()
            var rootHelperSocketPath: String? = null
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = TestVpnTunnelSession(events = events),
                        ),
                    rootHelperSocketPathProvider = { rootHelperSocketPath },
                )
            rootHelperSocketPath = "/data/user/0/com.poyka.ripdpi/files/root_helper.sock"

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(
                "/data/user/0/com.poyka.ripdpi/files/root_helper.sock",
                bridge.startedConfig?.rootHelperSocketPath,
            )
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
            assertEquals(2, bridge.stopCount)
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

    @Test
    fun stopClosesBridgeAndSessionEvenWhenBridgeStopFails() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events).apply { stopFailure = IllegalStateException("stop boom") }
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

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val failure = runCatching { runtime.stop() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, bridge.stopCount)
            assertTrue(session.closed)
            assertFalse(runtime.isRunning)
        }

    @Test
    fun startKeepsUdpRelayWhenQuicBypassStrategyIsDisabled() =
        runTest {
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals("udp", bridge.startedConfig?.socks5Udp)
            assertEquals(localProxyEndpoint.host, bridge.startedConfig?.socks5Address)
            assertEquals(localProxyEndpoint.port, bridge.startedConfig?.socks5Port)
            assertEquals(localProxyEndpoint.username, bridge.startedConfig?.username)
            assertEquals(localProxyEndpoint.password, bridge.startedConfig?.password)
        }

    @Test
    fun startKeepsUdpRelayWhenQuicBypassStrategyIsEnabled() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDesyncUdp(true)
                    .build()
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                settings.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals("udp", bridge.startedConfig?.socks5Udp)
        }

    // -- Error path tests -----------------------------------------------------

    @Test
    fun `start when already running throws`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val error =
                runCatching {
                    runtime.start(
                        AppSettingsSerializer.defaultValue.activeDnsSettings(),
                        overrideReason = null,
                        logContext = null,
                        localProxyEndpoint = localProxyEndpoint,
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
        }

    @Test
    fun `pollTelemetry returns engine error when bridge throws`() =
        runTest {
            val bridge = TestTun2SocksBridge().apply { telemetryFailure = IOException("telemetry crash") }
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val result = runtime.pollTelemetry()

            assertTrue(result is RuntimeTelemetryOutcome.EngineError)
        }

    @Test
    fun `pollTelemetry returns no data when tunnel is not running`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            assertEquals(RuntimeTelemetryOutcome.NoData, runtime.pollTelemetry())
        }

    @Test
    fun `stop is no-op when no tunnel session exists`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.stop()

            assertFalse(runtime.isRunning)
        }

    @Test
    fun `resetRuntimeState clears signature and counters`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            runtime.stop()
            runtime.start(
                AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            assertEquals(1L, runtime.tunnelRecoveryRetryCount)

            runtime.stop()
            runtime.resetRuntimeState()

            assertNull(runtime.currentDnsSignature)
            assertEquals(0L, runtime.tunnelRecoveryRetryCount)
        }
}
