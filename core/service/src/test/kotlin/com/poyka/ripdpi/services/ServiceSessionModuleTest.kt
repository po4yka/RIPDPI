package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServiceSessionModuleTest {
    @Test
    fun proxySessionModuleUsesInjectedFactories() =
        runTest {
            val relayFactory = RecordingUpstreamRelaySupervisorFactory()
            val warpFactory = RecordingWarpRuntimeSupervisorFactory()
            val proxyFactory = RecordingProxyRuntimeSupervisorFactory()
            val statusFactory = RecordingServiceStatusReporterFactory()
            val host = TestProxyServiceHost(backgroundScope)
            val upstreamRelaySupervisor = ProxyServiceSessionModule.provideUpstreamRelaySupervisor(host, relayFactory)
            val warpRuntimeSupervisor = ProxyServiceSessionModule.provideWarpRuntimeSupervisor(host, warpFactory)
            val proxyRuntimeSupervisor =
                ProxyServiceSessionModule.provideProxyRuntimeSupervisor(
                    host = host,
                    factory = proxyFactory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val statusReporter =
                ProxyServiceSessionModule.provideProxyStatusReporter(
                    serviceStateStore = TestServiceStateStore(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    factory = statusFactory,
                )
            val coordinator =
                ProxyServiceSessionModule.provideProxyCoordinator(
                    host = host,
                    connectionPolicyResolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.Proxy)),
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    networkHandoverMonitor = TestNetworkHandoverMonitor(),
                    policyHandoverEventStore = TestPolicyHandoverEventStore(),
                    permissionWatchdog = TestPermissionWatchdog(),
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                    statusReporter = statusReporter,
                    screenStateObserver = TestScreenStateObserver(),
                )

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(1, relayFactory.createCalls)
            assertEquals(1, warpFactory.createCalls)
            assertEquals(Mode.Proxy, statusFactory.createdModes.single())
            assertEquals(Sender.Proxy, statusFactory.createdSenders.single())
            assertNotNull(coordinator)
        }

    @Test
    fun vpnSessionModuleUsesInjectedFactories() =
        runTest {
            val relayFactory = RecordingUpstreamRelaySupervisorFactory()
            val warpFactory = RecordingWarpRuntimeSupervisorFactory()
            val proxyFactory = RecordingProxyRuntimeSupervisorFactory()
            val statusFactory = RecordingServiceStatusReporterFactory()
            val overrides = TestResolverOverrideStore()
            val resolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN))
            val host = TestVpnServiceHost(backgroundScope)
            val runtimeDependencies =
                VpnServiceRuntimeRuntimeDependencies(
                    appSettingsRepository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
                    connectionPolicyResolver = resolver,
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(),
                    resolverOverrideStore = overrides,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    networkHandoverMonitor = TestNetworkHandoverMonitor(),
                    policyHandoverEventStore = TestPolicyHandoverEventStore(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    dnsDependencies =
                        VpnServiceRuntimeDnsDependencies(
                            networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                            networkDnsBlockedPathStore = TestNetworkDnsBlockedPathStore(),
                            resolverRefreshPlanner =
                                VpnResolverRefreshPlanner(
                                    connectionPolicyResolver = resolver,
                                    resolverOverrideStore = overrides,
                                ),
                        ),
                    upstreamRelaySupervisorFactory = relayFactory,
                    warpRuntimeSupervisorFactory = warpFactory,
                    proxyRuntimeSupervisorFactory = proxyFactory,
                    screenStateObserver = TestScreenStateObserver(),
                )
            val statusDependencies =
                VpnServiceRuntimeStatusDependencies(
                    serviceStateStore = TestServiceStateStore(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    serviceStatusReporterFactory = statusFactory,
                )
            val vpnTunnelRuntime =
                VpnServiceSessionModule.provideVpnTunnelRuntime(
                    host = host,
                    dependencies = runtimeDependencies,
                )
            val encryptedDnsFailoverController =
                VpnServiceSessionModule.provideVpnEncryptedDnsFailoverController(
                    runtimeDependencies = runtimeDependencies,
                    statusDependencies = statusDependencies,
                )
            val upstreamRelaySupervisor =
                VpnServiceSessionModule.provideVpnUpstreamRelaySupervisor(host, relayFactory)
            val warpRuntimeSupervisor =
                VpnServiceSessionModule.provideVpnWarpRuntimeSupervisor(host, warpFactory)
            val proxyRuntimeSupervisor =
                VpnServiceSessionModule.provideVpnProxyRuntimeSupervisor(
                    host = host,
                    factory = proxyFactory,
                    dependencies = runtimeDependencies,
                )
            val statusReporter =
                VpnServiceSessionModule.provideVpnStatusReporter(statusDependencies)
            val coordinator =
                VpnServiceSessionModule.provideVpnCoordinator(
                    host = host,
                    runtimeDependencies = runtimeDependencies,
                    statusDependencies = statusDependencies,
                    permissionWatchdog = TestPermissionWatchdog(),
                    vpnTunnelRuntime = vpnTunnelRuntime,
                    encryptedDnsFailoverController = encryptedDnsFailoverController,
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                    statusReporter = statusReporter,
                )

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(1, relayFactory.createCalls)
            assertEquals(1, warpFactory.createCalls)
            assertEquals(Mode.VPN, statusFactory.createdModes.single())
            assertEquals(Sender.VPN, statusFactory.createdSenders.single())
            assertNotNull(coordinator)
        }

    private class RecordingUpstreamRelaySupervisorFactory :
        UpstreamRelaySupervisorFactory(
            TestRipDpiRelayFactory(),
            TestRelayProfileStore(),
            TestRelayCredentialStore(),
        ) {
        var createCalls: Int = 0

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): UpstreamRelaySupervisor {
            createCalls += 1
            return UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = TestRipDpiRelayFactory(),
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                relayProfileStore = TestRelayProfileStore(),
                relayCredentialStore = TestRelayCredentialStore(),
            )
        }
    }

    private class RecordingWarpRuntimeSupervisorFactory :
        WarpRuntimeSupervisorFactory(
            TestRipDpiWarpFactory(),
            TestWarpRuntimeConfigResolver(),
        ) {
        var createCalls: Int = 0

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): WarpRuntimeSupervisor {
            createCalls += 1
            return WarpRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                warpFactory = TestRipDpiWarpFactory(),
                runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
            )
        }
    }

    private class RecordingProxyRuntimeSupervisorFactory : ProxyRuntimeSupervisorFactory {
        var createCalls: Int = 0

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            networkSnapshotProvider: NativeNetworkSnapshotProvider,
        ): ProxyRuntimeSupervisor {
            createCalls += 1
            return ProxyRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                ripDpiProxyFactory =
                    object : RipDpiProxyFactory {
                        override fun create() = TestProxyRuntime()
                    },
                networkSnapshotProvider = networkSnapshotProvider,
            )
        }
    }

    private class RecordingServiceStatusReporterFactory : ServiceStatusReporterFactory {
        val createdModes = mutableListOf<Mode>()
        val createdSenders = mutableListOf<Sender>()

        override fun create(
            mode: Mode,
            sender: Sender,
            serviceStateStore: ServiceStateStore,
            networkFingerprintProvider: NetworkFingerprintProvider,
            telemetryFingerprintHasher: TelemetryFingerprintHasher,
            clock: ServiceClock,
        ): ServiceStatusReporter {
            createdModes += mode
            createdSenders += sender
            return ServiceStatusReporter(
                mode = mode,
                sender = sender,
                serviceStateStore = serviceStateStore,
                networkFingerprintProvider = networkFingerprintProvider,
                telemetryFingerprintHasher = telemetryFingerprintHasher,
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
                clock = clock,
            )
        }
    }
}
