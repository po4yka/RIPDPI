package com.poyka.ripdpi.services

import android.os.Build
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
    internal companion object {
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
            val receiptStore = VpnTunnelAppliedNetworkReceiptStore()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                    appliedNetworkReceiptStore = receiptStore,
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
            val initialReceiptGeneration = checkNotNull(receiptStore.snapshot()).generation
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
            assertEquals(initialReceiptGeneration + 1, receiptStore.snapshot()?.generation)
        }

    @Test
    fun startPassesAdaptiveTunnelMtuToNativeConfig() =
        runTest {
            val parameters =
                VpnTunnelNetworkParameters(
                    tunnelMtu = 1_320,
                    metered = true,
                    appliedEncapsulationBudgetBytes = 80,
                )
            val host =
                TestVpnServiceHost(backgroundScope).apply {
                    tunnelNetworkParameters = parameters
                }
            val bridge = TestTun2SocksBridge()
            val sessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession())
            val receiptStore = VpnTunnelAppliedNetworkReceiptStore()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = sessionProvider,
                    appliedNetworkReceiptStore = receiptStore,
                    sdkInt = Build.VERSION_CODES.Q,
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(1_320, bridge.startedConfig?.tunnelMtu)
            assertEquals(parameters, sessionProvider.lastNetworkParameters)
            assertEquals(1_320, receiptStore.snapshot()?.appliedTunnelMtu)
            assertEquals(80, receiptStore.snapshot()?.appliedEncapsulationBudgetBytes)
            assertEquals(true, receiptStore.snapshot()?.metered)
            assertEquals("tun2socks", receiptStore.snapshot()?.effectiveEgress)
        }

    @Test
    fun `pre API 29 runtime receipt omits unapplied metered state`() =
        runTest {
            val parameters = VpnTunnelNetworkParameters(tunnelMtu = 1_320, metered = true)
            val host =
                TestVpnServiceHost(backgroundScope).apply {
                    tunnelNetworkParameters = parameters
                }
            val receiptStore = VpnTunnelAppliedNetworkReceiptStore()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge()),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                    appliedNetworkReceiptStore = receiptStore,
                    sdkInt = Build.VERSION_CODES.P,
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertNull(receiptStore.snapshot()?.metered)
        }

    @Test
    fun `applied MTU receipt exists only while native forwarding is ready`() =
        runTest {
            val receiptStore = VpnTunnelAppliedNetworkReceiptStore()
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                    appliedNetworkReceiptStore = receiptStore,
                )

            assertNull(receiptStore.snapshot())
            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            assertTrue(receiptStore.snapshot() != null)

            runtime.retainFailClosedBarrier()
            assertNull(receiptStore.snapshot())
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
                    environment =
                        VpnTunnelRuntimeEnvironment(
                            rootHelperSocketPathProvider = { rootHelperSocketPath },
                        ),
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
