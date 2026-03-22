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
                    proxyRuntimeSupervisorFactory = proxyFactory,
                    serviceStatusReporterFactory = statusFactory,
                )

            coordinatorFactory.create(host)

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(Mode.Proxy, statusFactory.createdModes.single())
            assertEquals(Sender.Proxy, statusFactory.createdSenders.single())
        }

    @Test
    fun vpnCoordinatorFactoryUsesInjectedFactories() =
        runTest {
            val proxyFactory = RecordingProxyRuntimeSupervisorFactory()
            val statusFactory = RecordingServiceStatusReporterFactory()
            val overrides = TestResolverOverrideStore()
            val resolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN))
            val host = TestVpnServiceHost(backgroundScope)
            val coordinatorFactory =
                VpnServiceRuntimeCoordinatorFactory(
                    appSettingsRepository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
                    connectionPolicyResolver = resolver,
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    serviceStateStore = TestServiceStateStore(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(),
                    resolverOverrideStore = overrides,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    networkHandoverMonitor = TestNetworkHandoverMonitor(),
                    policyHandoverEventStore = TestPolicyHandoverEventStore(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    resolverRefreshPlanner =
                        VpnResolverRefreshPlanner(
                            connectionPolicyResolver = resolver,
                            resolverOverrideStore = overrides,
                        ),
                    proxyRuntimeSupervisorFactory = proxyFactory,
                    serviceStatusReporterFactory = statusFactory,
                )

            coordinatorFactory.create(host)

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(Mode.VPN, statusFactory.createdModes.single())
            assertEquals(Sender.VPN, statusFactory.createdSenders.single())
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
