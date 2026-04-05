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
import org.junit.Test

class ServiceRuntimeCoordinatorFactoryTest {
    @Test
    fun proxyCoordinatorFactoryUsesInjectedFactories() =
        runTest {
            val warpFactory = RecordingWarpRuntimeSupervisorFactory()
            val proxyFactory = RecordingProxyRuntimeSupervisorFactory()
            val statusFactory = RecordingServiceStatusReporterFactory()
            val host = TestProxyServiceHost(backgroundScope)
            val coordinatorFactory =
                ProxyServiceRuntimeCoordinatorFactory(
                    connectionPolicyResolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.Proxy)),
                    serviceStateStore = TestServiceStateStore(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    networkHandoverMonitor = TestNetworkHandoverMonitor(),
                    policyHandoverEventStore = TestPolicyHandoverEventStore(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    warpRuntimeSupervisorFactory = warpFactory,
                    proxyRuntimeSupervisorFactory = proxyFactory,
                    serviceStatusReporterFactory = statusFactory,
                    permissionWatchdog = TestPermissionWatchdog(),
                    screenStateObserver = TestScreenStateObserver(),
                )

            coordinatorFactory.create(host)

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(1, warpFactory.createCalls)
            assertEquals(Mode.Proxy, statusFactory.createdModes.single())
            assertEquals(Sender.Proxy, statusFactory.createdSenders.single())
        }

    @Test
    fun vpnCoordinatorFactoryUsesInjectedFactories() =
        runTest {
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
            val coordinatorFactory =
                VpnServiceRuntimeCoordinatorFactory(
                    runtimeDependencies = runtimeDependencies,
                    statusDependencies = statusDependencies,
                    permissionWatchdog = TestPermissionWatchdog(),
                )

            coordinatorFactory.create(host)

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(1, warpFactory.createCalls)
            assertEquals(Mode.VPN, statusFactory.createdModes.single())
            assertEquals(Sender.VPN, statusFactory.createdSenders.single())
        }

    private class RecordingWarpRuntimeSupervisorFactory : WarpRuntimeSupervisorFactory(TestRipDpiWarpFactory()) {
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
                clock = clock,
            )
        }
    }
}
