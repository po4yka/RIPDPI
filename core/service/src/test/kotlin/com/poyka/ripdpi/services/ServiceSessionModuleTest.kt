package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeDnsDependencies
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeRuntimeDependencies
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeStatusDependencies
import com.poyka.ripdpi.service.session.proxy.ProxyServiceSessionModule
import com.poyka.ripdpi.service.session.vpn.VpnServiceSessionModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
            val dispatchers = testDispatchers()
            val upstreamRelaySupervisor =
                ProxyServiceSessionModule.provideUpstreamRelaySupervisor(host, relayFactory, dispatchers)
            val warpRuntimeSupervisor =
                ProxyServiceSessionModule.provideWarpRuntimeSupervisor(host, warpFactory, dispatchers)
            val proxyRuntimeSupervisor =
                ProxyServiceSessionModule.provideProxyRuntimeSupervisor(
                    host = host,
                    factory = proxyFactory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    dispatchers = dispatchers,
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
                    directPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
                    rootHelperManager = RootHelperManager(),
                )

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(1, relayFactory.createCalls)
            assertEquals(1, warpFactory.createCalls)
            assertSame(dispatchers.io, proxyFactory.createdDispatchers.single())
            assertSame(dispatchers.io, relayFactory.createdDispatchers.single())
            assertSame(dispatchers.io, warpFactory.createdDispatchers.single())
            assertEquals(Mode.Proxy, statusFactory.createdModes.single())
            assertEquals(Sender.Proxy, statusFactory.createdSenders.single())
            assertNotNull(coordinator)
        }

    @Test
    fun bootstrapProxySessionModuleUsesInjectedDispatcher() =
        runTest {
            val proxyFactory = RecordingProxyRuntimeSupervisorFactory()
            val dispatchers = testDispatchers()

            val supervisor =
                BootstrapProxySessionModule.provideBootstrapProxyRuntimeSupervisor(
                    sessionScope = backgroundScope,
                    factory = proxyFactory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    dispatchers = dispatchers,
                )

            assertEquals(1, proxyFactory.createCalls)
            assertSame(dispatchers.io, proxyFactory.createdDispatchers.single())
            assertNotNull(supervisor)
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
            val dispatchers = testDispatchers()
            val runtimeDependencies =
                createVpnRuntimeDependencies(
                    resolver = resolver,
                    overrides = overrides,
                    relayFactory = relayFactory,
                    warpFactory = warpFactory,
                    proxyFactory = proxyFactory,
                )
            val statusDependencies = createVpnStatusDependencies(statusFactory)
            val protectSocketServer =
                VpnProtectSocketServer(
                    socketPath = "/tmp/ripdpi-test-protect.sock",
                    protectFailureMonitor = InMemoryVpnProtectFailureMonitor(),
                    fdProtector = { true },
                )
            val vpnTunnelRuntime =
                VpnServiceSessionModule.provideVpnTunnelRuntime(
                    host = host,
                    dependencies = runtimeDependencies,
                    protectSocketServer = protectSocketServer,
                    rootHelperManager = RootHelperManager(),
                )
            val encryptedDnsFailoverController =
                VpnServiceSessionModule.provideVpnEncryptedDnsFailoverController(
                    runtimeDependencies = runtimeDependencies,
                    statusDependencies = statusDependencies,
                )
            val upstreamRelaySupervisor =
                VpnServiceSessionModule.provideVpnUpstreamRelaySupervisor(host, relayFactory, dispatchers)
            val warpRuntimeSupervisor =
                VpnServiceSessionModule.provideVpnWarpRuntimeSupervisor(host, warpFactory, dispatchers)
            val proxyRuntimeSupervisor =
                VpnServiceSessionModule.provideVpnProxyRuntimeSupervisor(
                    host = host,
                    factory = proxyFactory,
                    dependencies = runtimeDependencies,
                    dispatchers = dispatchers,
                )
            val statusReporter =
                VpnServiceSessionModule.provideVpnStatusReporter(statusDependencies)
            val vpnProtectFailureMonitor = VpnServiceSessionModule.provideVpnProtectFailureMonitor()
            val coordinator =
                VpnServiceSessionModule.provideVpnCoordinator(
                    host = host,
                    runtimeDependencies = runtimeDependencies,
                    permissionWatchdog = TestPermissionWatchdog(),
                    vpnProtectFailureMonitor = vpnProtectFailureMonitor,
                    vpnTunnelRuntime = vpnTunnelRuntime,
                    encryptedDnsFailoverController = encryptedDnsFailoverController,
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                    statusReporter = statusReporter,
                    directPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
                    rootHelperManager = RootHelperManager(),
                )

            assertEquals(1, proxyFactory.createCalls)
            assertEquals(1, relayFactory.createCalls)
            assertEquals(1, warpFactory.createCalls)
            assertSame(dispatchers.io, proxyFactory.createdDispatchers.single())
            assertSame(dispatchers.io, relayFactory.createdDispatchers.single())
            assertSame(dispatchers.io, warpFactory.createdDispatchers.single())
            assertEquals(Mode.VPN, statusFactory.createdModes.single())
            assertEquals(Sender.VPN, statusFactory.createdSenders.single())
            assertNotNull(coordinator)
        }

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = StandardTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }

    private fun createVpnRuntimeDependencies(
        resolver: TestConnectionPolicyResolver,
        overrides: TestResolverOverrideStore,
        relayFactory: RecordingUpstreamRelaySupervisorFactory,
        warpFactory: RecordingWarpRuntimeSupervisorFactory,
        proxyFactory: RecordingProxyRuntimeSupervisorFactory,
    ): VpnServiceRuntimeRuntimeDependencies =
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
            dnsDependencies = createVpnDnsDependencies(resolver, overrides),
            upstreamRelaySupervisorFactory = relayFactory,
            warpRuntimeSupervisorFactory = warpFactory,
            proxyRuntimeSupervisorFactory = proxyFactory,
            screenStateObserver = TestScreenStateObserver(),
        )

    private fun createVpnDnsDependencies(
        resolver: TestConnectionPolicyResolver,
        overrides: TestResolverOverrideStore,
    ): VpnServiceRuntimeDnsDependencies =
        VpnServiceRuntimeDnsDependencies(
            networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
            networkDnsBlockedPathStore = TestNetworkDnsBlockedPathStore(),
            resolverRefreshPlanner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = resolver,
                    resolverOverrideStore = overrides,
                ),
        )

    private fun createVpnStatusDependencies(
        statusFactory: RecordingServiceStatusReporterFactory,
    ): VpnServiceRuntimeStatusDependencies =
        VpnServiceRuntimeStatusDependencies(
            serviceStateStore = TestServiceStateStore(),
            networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
            telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
            serviceStatusReporterFactory = statusFactory,
        )

    private class RecordingUpstreamRelaySupervisorFactory :
        UpstreamRelaySupervisorFactory(
            TestRipDpiRelayFactory(),
            TestRelayProfileStore(),
            TestRelayCredentialStore(),
        ) {
        var createCalls: Int = 0
        val createdDispatchers = mutableListOf<CoroutineDispatcher>()

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): UpstreamRelaySupervisor {
            createCalls += 1
            createdDispatchers += dispatcher
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
        val createdDispatchers = mutableListOf<CoroutineDispatcher>()

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): WarpRuntimeSupervisor {
            createCalls += 1
            createdDispatchers += dispatcher
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
        val createdDispatchers = mutableListOf<CoroutineDispatcher>()

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            networkSnapshotProvider: NativeNetworkSnapshotProvider,
        ): ProxyRuntimeSupervisor {
            createCalls += 1
            createdDispatchers += dispatcher
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
