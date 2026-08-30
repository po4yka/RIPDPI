package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiXrayRuntime
import com.poyka.ripdpi.core.XrayProviderOrchestrator
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
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
import com.poyka.ripdpi.service.session.vpn.VpnCoordinatorServices
import com.poyka.ripdpi.service.session.vpn.VpnServiceSessionModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
                    stateInitializer =
                        ServiceSessionStateInitializer(TestServiceStateStore()).also {
                            it.initialize(Mode.Proxy)
                        },
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    factory = statusFactory,
                )
            val amneziaWgRuntimeSupervisor =
                ProxyServiceSessionModule.provideProxyAmneziaWgRuntimeSupervisor(
                    host = host,
                    factory = NoOpAmneziaWgRuntimeSupervisorFactory(),
                    dispatchers = dispatchers,
                )
            val supervisors =
                ProxyServiceSessionModule.provideProxyRuntimeSupervisorBundle(
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    amneziaWgRuntimeSupervisor = amneziaWgRuntimeSupervisor,
                    proxyRuntimeSupervisor = proxyRuntimeSupervisor,
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
                    supervisors = supervisors,
                    autolearnActivationReceiptPublisher = testAutolearnActivationReceiptPublisher(),
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
            assertEquals(
                RelayRuntimeNetworkMode.Proxy,
                relayFactory.networkModes.single(),
            )
            assertSame(dispatchers.io, warpFactory.createdDispatchers.single())
            assertEquals(Mode.Proxy, statusFactory.createdModes.single())
            assertEquals(Sender.Proxy, statusFactory.createdSenders.single())
            assertNotNull(coordinator)
        }

    // T1 — proxy session graph must not wire VPN-only infrastructure.
    // VpnProtectSocketServer and VpnTunnelRuntime are VPN-specific: the proxy module
    // must not declare @Provides methods for them.  We verify this structurally so
    // a future accidental addition is caught at test time without running a full DI
    // graph that requires an Android service context.
    @Test
    fun proxySessionModuleDoesNotProvideVpnProtectSocketServer() {
        val methodNames = ProxyServiceSessionModule::class.java.declaredMethods.map { it.name }
        assertTrue(
            "ProxyServiceSessionModule must not have a provideVpnProtectSocketServer method",
            methodNames.none { it.contains("VpnProtect", ignoreCase = true) },
        )
    }

    @Test
    fun proxySessionModuleDoesNotProvideVpnTunnelRuntime() {
        val methodNames = ProxyServiceSessionModule::class.java.declaredMethods.map { it.name }
        assertTrue(
            "ProxyServiceSessionModule must not have a provideVpnTunnelRuntime method",
            methodNames.none { it.contains("VpnTunnel", ignoreCase = true) },
        )
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
    @Suppress("LongMethod")
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
                VpnServiceSessionModule.createVpnTunnelRuntime(
                    host = host,
                    dependencies = runtimeDependencies,
                    protectSocketServer = protectSocketServer,
                    rootHelperManager = RootHelperManager(),
                    flowAttributionBridge = testFlowAttributionBridge(),
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
                VpnServiceSessionModule.provideVpnStatusReporter(
                    dependencies = statusDependencies,
                    stateInitializer =
                        ServiceSessionStateInitializer(statusDependencies.serviceStateStore).also {
                            it.initialize(Mode.VPN)
                        },
                )
            val vpnProtectFailureMonitor = VpnServiceSessionModule.provideVpnProtectFailureMonitor()
            val coordinator =
                VpnServiceSessionModule.provideVpnCoordinator(
                    host = host,
                    runtimeDependencies = runtimeDependencies,
                    permissionWatchdog = TestPermissionWatchdog(),
                    vpnProtectFailureMonitor = vpnProtectFailureMonitor,
                    vpnTunnelRuntime = vpnTunnelRuntime,
                    encryptedDnsFailoverController = encryptedDnsFailoverController,
                    transportFailoverApplyTracker = TransportFailoverApplyTracker(),
                    services =
                        VpnCoordinatorServices(
                            upstreamRelaySupervisor = upstreamRelaySupervisor,
                            warpRuntimeSupervisor = warpRuntimeSupervisor,
                            amneziaWgRuntimeSupervisor =
                                VpnServiceSessionModule.provideVpnAmneziaWgRuntimeSupervisor(
                                    host,
                                    NoOpAmneziaWgRuntimeSupervisorFactory(),
                                    dispatchers,
                                ),
                            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                            autolearnActivationReceiptPublisher = testAutolearnActivationReceiptPublisher(),
                            statusReporter = statusReporter,
                            directPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
                            rootHelperManager = RootHelperManager(),
                            xrayProviderSessionController = buildTestXrayProviderSessionController(vpnTunnelRuntime),
                        ),
                )

            assertVpnFactoriesInvoked(proxyFactory, relayFactory, warpFactory, statusFactory, dispatchers)
            assertNotNull(coordinator)
        }

    private fun assertVpnFactoriesInvoked(
        proxyFactory: RecordingProxyRuntimeSupervisorFactory,
        relayFactory: RecordingUpstreamRelaySupervisorFactory,
        warpFactory: RecordingWarpRuntimeSupervisorFactory,
        statusFactory: RecordingServiceStatusReporterFactory,
        dispatchers: AppCoroutineDispatchers,
    ) {
        assertEquals(1, proxyFactory.createCalls)
        assertEquals(1, relayFactory.createCalls)
        assertEquals(1, warpFactory.createCalls)
        assertSame(dispatchers.io, proxyFactory.createdDispatchers.single())
        assertSame(dispatchers.io, relayFactory.createdDispatchers.single())
        assertEquals(
            RelayRuntimeNetworkMode.Vpn,
            relayFactory.networkModes.single(),
        )
        assertSame(dispatchers.io, warpFactory.createdDispatchers.single())
        assertEquals(Mode.VPN, statusFactory.createdModes.single())
        assertEquals(Sender.VPN, statusFactory.createdSenders.single())
    }

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = StandardTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }

    /**
     * Build an [XrayProviderSessionController] for the coordinator-wiring test
     * using the offline test fakes (no Android Context / Keystore / gomobile).
     * The session itself is never started here — the test only asserts the
     * coordinator constructs — so a fake bridge/tunnel/stores suffice.
     */
    private fun buildTestXrayProviderSessionController(
        vpnTunnelRuntime: VpnTunnelRuntime,
    ): XrayProviderSessionController {
        val bridge = FakeXrayNativeBridge()
        val selectionStore = FakeSelectionStore()
        val profileStore = FakeDurableXrayProfileStore()
        val renderedConfigHolder = XrayRenderedConfigHolder()
        val startParamsHolder = XrayTunnelStartParamsHolder()
        val owner = XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined)
        val orchestrator =
            XrayProviderOrchestrator(
                xrayRuntimeFactory = { cfg -> RipDpiXrayRuntime(owner, cfg) },
                tunnel = XrayManagedTunnel(vpnTunnelRuntime, startParamsHolder::require),
                protectController = { true },
                renderedConfigProvider = { renderedConfigHolder.require() },
            )
        return XrayProviderSessionController(
            readSelectedProfile = {
                val selection = selectionStore.current()
                XraySelectedProfile(
                    selection,
                    if (selection.kind ==
                        com.poyka.ripdpi.data.xray.VpnProviderKind.Xray
                    ) {
                        profileStore.load(selection.activeProfileId)
                    } else {
                        null
                    },
                )
            },
            routeBuilder = XrayProviderRouteBuilder(resolveEndpoint = { listOf("192.0.2.1") }),
            orchestrator = orchestrator,
            snapshotDeriver = XrayProviderSnapshotDeriver(),
            probeRunner = XrayProviderDiagnosticsProbeRunner(),
            startParamsHolder = startParamsHolder,
            runtimeOwner = owner,
            renderedConfigSink = { renderedConfigHolder.current = it },
            lastProtectFailureDetail = { null },
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
            proxyGroupRepository = TestProxyGroupRepository(),
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
            amneziaWgRuntimeSupervisorFactory = NoOpAmneziaWgRuntimeSupervisorFactory(),
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
        val networkModes = mutableListOf<RelayRuntimeNetworkMode>()

        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            networkMode: RelayRuntimeNetworkMode,
        ): UpstreamRelaySupervisor {
            createCalls += 1
            createdDispatchers += dispatcher
            networkModes += networkMode
            return UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = TestRipDpiRelayFactory(),
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                relayProfileStore = TestRelayProfileStore(),
                relayCredentialStore = TestRelayCredentialStore(),
                networkMode = networkMode,
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

private fun testFlowAttributionBridge(): FlowAttributionBridge =
    FlowAttributionBridge(
        NoOpFlowAppAttributionStore,
        null,
        SoBindToDeviceUidPolicyEligibility.forTest(
            sdkInt = android.os.Build.VERSION_CODES.S,
            kernelRelease = "5.10.0-android",
            probe = { BindToDeviceProbeOutcome.Supported },
        ),
    )

/** No-op [AmneziaWgRuntimeSupervisorFactory] for module wiring tests — proxy mode never starts AWG. */
private class NoOpAmneziaWgRuntimeSupervisorFactory :
    AmneziaWgRuntimeSupervisorFactory(
        NoOpRipDpiAmneziaWgFactory(),
        TestAmneziaWgRuntimeConfigResolver(),
    )

/** No-op [FlowAppAttributionStore] for constructing a [FlowAttributionBridge] in module wiring tests. */
internal object NoOpFlowAppAttributionStore : FlowAppAttributionStore {
    override fun noteFlow(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ) = Unit

    override fun resolveFlowUidOnly(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int = InvalidUid

    override fun lookup(ipSetDigest: String): FlowAttribution.Attributed? = null

    override fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    ) = Unit

    override fun clear() = Unit
}
