package com.poyka.ripdpi.services

import android.Manifest
import android.app.Service
import com.poyka.ripdpi.data.DefaultDeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.service.runtime.proxy.ProxyRuntimeSupervisorBundle
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
import com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener
import dagger.hilt.internal.GeneratedComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Executes the real Android service lifecycle with active-session factories forbidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PausedServiceReconstructionTest {
    @Test
    fun `proxy paused recovery creates no active session native root or selector graph`() =
        runBlocking {
            val fixture = Fixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            skipGeneratedInjection(service)
            service.serviceStateStore = fixture.state
            service.pauseAuthority = fixture.authority
            service.pauseController = fixture.pause
            service.profileRecovery = fixture.recovery
            service.runtimeEvidenceReporter = fixture.reporter
            service.sessionComponentBuilderProvider = fixture.forbidden("proxy active session")
            service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors and probes")
            service.rootHelperManagerProvider = fixture.forbidden("native root helper")
            lifecycle.create()
            assertEquals(0, fixture.constructions.get())
            service.onStartCommand(null, 0, 1)
            withTimeout(5_000) { fixture.authority.states.first { it?.pause?.phase == PausePhase.Deferred } }
            assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
            assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
            lifecycle.destroy()
            awaitTrackedDestruction(service)
            service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            assertEquals(0, fixture.constructions.get())
        }

    @Test
    fun `vpn paused recovery creates no JNI TUN protect native or selector session graph`() =
        runBlocking {
            val fixture = Fixture(Mode.VPN)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            skipGeneratedInjection(service)
            service.serviceStateStore = fixture.state
            service.pauseAuthority = fixture.authority
            service.pauseController = fixture.pause
            service.profileMutationCoordinator = fixture.recovery
            service.runtimeEvidenceReporter = fixture.reporter
            service.liveVpnLockdownReader = LiveVpnLockdownReader()
            service.hardKillSwitchStateStore =
                SharedPreferencesAndroidHardKillSwitchStateStore(RuntimeEnvironment.getApplication())
            service.recoveryReceiptCollector =
                RemoteDeviceRecoveryReceiptCollector(
                    SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(RuntimeEnvironment.getApplication()),
                )
            service.activeProtectSocketPathProvider = ActiveProtectSocketPathProvider()
            service.sessionComponentBuilderProvider = fixture.forbidden("VPN JNI TUN protect native session")
            service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors and probes")
            service.rootHelperManagerProvider = fixture.forbidden("native root helper")
            lifecycle.create()
            assertEquals(0, fixture.constructions.get())
            service.onStartCommand(null, 0, 1)
            withTimeout(5_000) { fixture.authority.states.first { it?.pause?.phase == PausePhase.Deferred } }
            assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
            assertNull(service.activeProtectSocketPathProvider.current())
            lifecycle.destroy()
            awaitTrackedDestruction(service)
            service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            assertEquals(0, fixture.constructions.get())
        }

    @Test
    fun `proxy throwing graph factory releases attempted ownership without accessing unrelated providers`() =
        runBlocking {
            val fixture = Fixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializeProxy(service, fixture)
            val original = IllegalStateException("graph factory failed")
            service.sessionComponentBuilderProvider = Provider { throw original }
            lifecycle.create()
            try {
                assertSame(original, runCatching { initializeActiveSession(service) }.exceptionOrNull())
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertEquals(0, fixture.constructions.get())
            } finally {
                lifecycle.destroy()
                awaitTrackedDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
            assertEquals(0, fixture.constructions.get())
        }

    @Test
    fun `proxy runtime constructor failure closes the real initialized state before clearing references`() =
        runBlocking {
            val fixture = Fixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializeProxy(service, fixture)
            val initializer = ServiceSessionStateInitializer(fixture.state)
            val original = IllegalStateException("runtime constructor failed")
            val component =
                object : ProxyServiceSessionComponent, ProxyServiceSessionEntryPoint, GeneratedComponent {
                    override fun stateInitializer() = initializer

                    override fun coordinator(): ProxyServiceRuntimeCoordinator {
                        assertSame(fixture.state, initializer.requireInitialized())
                        throw original
                    }
                }
            service.sessionComponentBuilderProvider =
                Provider {
                    object : ProxyServiceSessionComponentBuilder {
                        override fun host(host: ServiceCoordinatorHost): ProxyServiceSessionComponentBuilder {
                            assertSame(service, host)
                            return this
                        }

                        override fun build(): ProxyServiceSessionComponent = component
                    }
                }
            lifecycle.create()
            try {
                assertSame(original, runCatching { initializeActiveSession(service) }.exceptionOrNull())
                assertTrue(runCatching { initializer.requireInitialized() }.exceptionOrNull() is IllegalStateException)
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertNull(ReflectionHelpers.getField<Any?>(service, "stateInitializer"))
                assertEquals(0, fixture.constructions.get())
            } finally {
                lifecycle.destroy()
                awaitTrackedDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `vpn throwing graph factory unregisters already started underlay and route callbacks`() =
        runBlocking {
            val fixture = Fixture(Mode.VPN)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializeVpn(service, fixture)
            val original = IllegalStateException("VPN graph factory failed")
            val routes = service.vpnRouteObservationAuthority
            var factoryCalls = 0
            service.sessionComponentBuilderProvider =
                Provider {
                    factoryCalls += 1
                    assertNotNull(ReflectionHelpers.getField<Any?>(service.underlyingNetworkBinder, "callback"))
                    assertNotNull(ReflectionHelpers.getField<Any?>(routes, "registeredCallback"))
                    throw original
                }
            lifecycle.create()
            try {
                assertSame(original, runCatching { initializeActiveSession(service) }.exceptionOrNull())
                assertEquals(1, factoryCalls)
                assertNull(ReflectionHelpers.getField<Any?>(service.underlyingNetworkBinder, "callback"))
                assertNull(ReflectionHelpers.getField<Any?>(routes, "registeredCallback"))
                assertNull(service.activeProtectSocketPathProvider.current())
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertEquals(0, fixture.constructions.get())
            } finally {
                lifecycle.destroy()
                awaitTrackedDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `throwing selector startup releases earlier and partially started listeners plus real session graph`() =
        runBlocking {
            val fixture = Fixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializeProxy(service, fixture)
            val initializer = ServiceSessionStateInitializer(fixture.state)
            val runtime = proxyCoordinator(service, fixture)
            service.sessionComponentBuilderProvider = componentProvider(service, initializer, runtime)
            val events = mutableListOf<String>()
            val first = FaultListener("first", events)
            val second = FaultListener("second", events, failStart = true)
            val reads = AtomicInteger()
            service.selectorRuntimeLifecycleListenersProvider =
                Provider {
                    reads.incrementAndGet()
                    linkedSetOf(first, second)
                }
            lifecycle.create()
            try {
                val failure = runCatching { initializeActiveSession(service) }.exceptionOrNull()
                assertSame(second.startFailure, failure)
                assertEquals(listOf("start first", "start second", "stop second", "stop first"), events)
                assertTrue(runCatching { initializer.requireInitialized() }.exceptionOrNull() is IllegalStateException)
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertNull(ReflectionHelpers.getField<Any?>(service, "shellDelegate"))
                assertEquals(1, reads.get())
            } finally {
                lifecycle.destroy()
                awaitTrackedDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
            assertEquals(1, reads.get())
        }

    @Test
    fun `failed listener rollback retains foreground ownership and Stop retries without losing handles`() =
        runBlocking {
            val fixture = Fixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializeProxy(service, fixture)
            val initializer = ServiceSessionStateInitializer(fixture.state)
            val runtime = proxyCoordinator(service, fixture)
            service.sessionComponentBuilderProvider = componentProvider(service, initializer, runtime)
            val events = mutableListOf<String>()
            val first = FaultListener("first", events)
            val second = FaultListener("second", events, failStart = true, failStop = true)
            val reads = AtomicInteger()
            service.selectorRuntimeLifecycleListenersProvider =
                Provider {
                    reads.incrementAndGet()
                    linkedSetOf(first, second)
                }
            lifecycle.create()
            try {
                val failure = runCatching { initializeActiveSession(service) }.exceptionOrNull()
                assertTrue(failure is ActiveSessionCleanupPendingException)
                assertSame(second.startFailure, failure?.cause)
                val ownership = ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership")
                assertTrue(ownership.hasOwnership)
                assertTrue(ownership.cleanupPending)
                assertEquals(
                    PausePhase.CleanupPending,
                    fixture.pause.cleanupPending.value
                        ?.phase,
                )
                assertNotNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertEquals(1, first.stops)
                second.failStop = false

                assertEquals(RuntimeStopOutcome.FullyReleased, fixture.pause.stop())
                assertEquals(2, second.stops)
                assertEquals(1, first.stops)
                assertFalse(ownership.hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertNull(ReflectionHelpers.getField<Any?>(service, "coordinator"))
                assertEquals(1, reads.get())
            } finally {
                lifecycle.destroy()
                awaitTrackedDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
            assertEquals(1, reads.get())
        }

    private fun componentProvider(
        service: RipDpiProxyService,
        initializer: ServiceSessionStateInitializer,
        runtime: ProxyServiceRuntimeCoordinator,
    ): Provider<ProxyServiceSessionComponentBuilder> {
        val component =
            object : ProxyServiceSessionComponent, ProxyServiceSessionEntryPoint, GeneratedComponent {
                override fun stateInitializer() = initializer

                override fun coordinator() = runtime
            }
        return Provider {
            object : ProxyServiceSessionComponentBuilder {
                override fun host(host: ServiceCoordinatorHost): ProxyServiceSessionComponentBuilder {
                    assertSame(service, host)
                    return this
                }

                override fun build(): ProxyServiceSessionComponent = component
            }
        }
    }

    private fun proxyCoordinator(
        service: RipDpiProxyService,
        fixture: Fixture,
    ): ProxyServiceRuntimeCoordinator {
        val dispatcher = Dispatchers.IO
        val root = RootHelperManager()
        service.rootHelperManagerProvider = Provider { root }
        val supervisors =
            ProxyRuntimeSupervisorBundle(
                UpstreamRelaySupervisor(
                    scope = service.serviceScope,
                    dispatcher = dispatcher,
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    runtimeConfigResolver = TestUpstreamRelayRuntimeConfigResolver(),
                ),
                WarpRuntimeSupervisor(
                    scope = service.serviceScope,
                    dispatcher = dispatcher,
                    warpFactory = TestRipDpiWarpFactory(),
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                ),
                AmneziaWgRuntimeSupervisor(
                    scope = service.serviceScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                    runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                ),
                ProxyRuntimeSupervisor(
                    scope = service.serviceScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                ),
            )
        return ProxyServiceRuntimeCoordinator(
            host = service,
            connectionPolicyResolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.Proxy)),
            serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
            rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
            networkHandoverMonitor = TestNetworkHandoverMonitor(),
            policyHandoverEventStore = TestPolicyHandoverEventStore(),
            permissionWatchdog = TestPermissionWatchdog(),
            supervisors = supervisors,
            autolearnActivationReceiptPublisher = testAutolearnActivationReceiptPublisher(),
            statusReporter =
                ServiceStatusReporter(
                    mode = Mode.Proxy,
                    sender = com.poyka.ripdpi.data.Sender.Proxy,
                    serviceStateStore = fixture.state,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    runtimeExperimentSelectionProvider =
                        object : RuntimeExperimentSelectionProvider {
                            override fun current() = RuntimeExperimentSelection()
                        },
                    clock = TestServiceClock(1_000),
                ),
            configurationLifecycle =
                RuntimeConfigurationLifecycle(
                    AppliedRuntimeConfigurationStore(testRuntimeAppliedReceiptConsumer(fixture.authority)),
                    RuntimeConfigurationIdentityFactory(),
                ),
            screenStateObserver = TestScreenStateObserver(),
            ioDispatcher = dispatcher,
            clock = TestServiceClock(1_000),
            rootHelperManager = root,
        )
    }

    private class FaultListener(
        private val name: String,
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
        var failStop: Boolean = false,
    ) : SelectorRuntimeLifecycleListener {
        val startFailure = IllegalStateException("selector startup failed")
        var stops = 0
            private set

        override fun start(owner: Mode) {
            assertEquals(Mode.Proxy, owner)
            events += "start $name"
            if (failStart) throw startFailure
        }

        override fun stop(owner: Mode) {
            assertEquals(Mode.Proxy, owner)
            stops += 1
            events += "stop $name"
            check(!failStop) { "selector cleanup failed" }
        }
    }

    private fun initializeProxy(
        service: RipDpiProxyService,
        fixture: Fixture,
    ) {
        skipGeneratedInjection(service)
        service.serviceStateStore = fixture.state
        service.pauseAuthority = fixture.authority
        service.pauseController = fixture.pause
        service.profileRecovery = fixture.recovery
        service.runtimeEvidenceReporter = fixture.reporter
        service.sessionComponentBuilderProvider = fixture.forbidden("proxy graph")
        service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors")
        service.rootHelperManagerProvider = fixture.forbidden("root helper")
        service.runtimeResumeIntentTracker = RuntimeResumeIntentTracker(fixture.authority)
        service.serviceIntentArbiter = fixture.arbiter
        service.acceptedUserStopRecorder =
            AcceptedUserStopRecorder(
                InMemoryBootSessionStateStore(),
                service.runtimeResumeIntentTracker,
                service.serviceIntentArbiter,
                fixture.recovery,
                fixture.authority,
            )
        service.serviceStopProvenanceRecorder = stopProvenanceRecorder()
    }

    private fun initializeVpn(
        service: RipDpiVpnService,
        fixture: Fixture,
    ) {
        skipGeneratedInjection(service)
        service.serviceStateStore = fixture.state
        service.pauseAuthority = fixture.authority
        service.pauseController = fixture.pause
        service.profileMutationCoordinator = fixture.recovery
        service.runtimeEvidenceReporter = fixture.reporter
        service.liveVpnLockdownReader = LiveVpnLockdownReader()
        service.hardKillSwitchStateStore =
            SharedPreferencesAndroidHardKillSwitchStateStore(RuntimeEnvironment.getApplication())
        service.recoveryReceiptCollector =
            RemoteDeviceRecoveryReceiptCollector(
                SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(RuntimeEnvironment.getApplication()),
            )
        service.activeProtectSocketPathProvider = ActiveProtectSocketPathProvider()
        service.directDnsUnderlayAuthority = DirectDnsUnderlayAuthority()
        service.vpnRouteObservationAuthority =
            VpnRouteObservationAuthority(RuntimeEnvironment.getApplication(), VpnRouteLifecycleReceiptStore())
        service.runtimeResumeIntentTracker = RuntimeResumeIntentTracker(fixture.authority)
        service.serviceIntentArbiter = fixture.arbiter
        service.acceptedUserStopRecorder =
            AcceptedUserStopRecorder(
                InMemoryBootSessionStateStore(),
                service.runtimeResumeIntentTracker,
                service.serviceIntentArbiter,
                fixture.recovery,
                fixture.authority,
            )
        service.transportFailoverApplyTracker = TransportFailoverApplyTracker()
        service.explicitUserStartPreparer = Optional.empty()
        service.serviceRecoveryStartGate = Optional.empty()
        service.serviceStopProvenanceRecorder = stopProvenanceRecorder()
        service.sessionComponentBuilderProvider = fixture.forbidden("VPN graph")
        service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors")
        service.rootHelperManagerProvider = fixture.forbidden("root helper")
    }

    private fun stopProvenanceRecorder(): RoomServiceStopProvenanceRecorder =
        RoomServiceStopProvenanceRecorder(
            object : DiagnosticsArtifactWriteStore {
                override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

                override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

                override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

                override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) = Unit

                override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit
            },
            AndroidRuntimeEvidenceClock { 1_000 },
        )

    private suspend fun awaitTrackedDestruction(service: Service) {
        val cleanupJob = checkNotNull(ReflectionHelpers.getField<Job?>(service, "destroyCleanupJob"))
        withTimeout(5_000) { cleanupJob.join() }
        assertTrue(cleanupJob.isCompleted)
        assertFalse(cleanupJob.isCancelled)
    }

    private suspend fun initializeActiveSession(service: Service) {
        val mutex = ReflectionHelpers.getField<Mutex>(service, "entryMutex")
        mutex.withLock {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method =
                    service.javaClass.getDeclaredMethod("ensureActiveSession", Continuation::class.java).apply {
                        isAccessible =
                            true
                    }
                try {
                    method.invoke(service, continuation)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        }
    }

    private fun skipGeneratedInjection(service: Service) {
        // Unit construction supplies the shell explicitly; Full/Simple Hilt compilation verifies production injection.
        ReflectionHelpers.setField(service, "injected", true)
    }

    private class Fixture(
        mode: Mode,
    ) {
        val constructions = AtomicInteger()
        val authority =
            testPauseAuthority().apply {
                val intent = begin(mode, 300_000, snapshotAuthority())
                check(transition(intent, PausePhase.Paused, null))
                checkNotNull(claimResume(intent, true))
            }
        val arbiter = ServiceIntentArbiter(authority)
        val state = TestServiceStateStore()
        val recovery = RecoveryOnlyProfileMutationCoordinator {}
        val reporter =
            AndroidRuntimeEvidenceReporter(DefaultDeviceRuntimeEvidenceStore(), AndroidRuntimeEvidenceClock { 1_000 })
        val pause =
            TimedPauseController(
                RuntimeEnvironment.getApplication(),
                authority,
                recovery,
                state,
                LiveVpnLockdownReader(),
                arbiter,
            )

        fun <T> forbidden(name: String): Provider<T> =
            Provider {
                constructions.incrementAndGet()
                error("Paused shell constructed $name")
            }
    }
}
