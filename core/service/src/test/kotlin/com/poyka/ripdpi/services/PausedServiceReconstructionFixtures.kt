package com.poyka.ripdpi.services

import android.app.Service
import com.poyka.ripdpi.data.DefaultDeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.Sender
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal fun pausedProxyComponentProvider(
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

internal fun pausedProxyCoordinator(
    service: RipDpiProxyService,
    fixture: PausedServiceReconstructionFixture,
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
                activeRegistry = ActiveAwgRuntimeRegistry(),
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
                sender = Sender.Proxy,
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

internal class ReconstructionFaultListener(
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

internal fun initializePausedProxyService(
    service: RipDpiProxyService,
    fixture: PausedServiceReconstructionFixture,
) {
    skipPausedServiceInjection(service)
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
    service.serviceStopProvenanceRecorder = pausedServiceStopProvenanceRecorder()
}

internal fun initializePausedVpnService(
    service: RipDpiVpnService,
    fixture: PausedServiceReconstructionFixture,
) {
    skipPausedServiceInjection(service)
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
    service.serviceStopProvenanceRecorder = pausedServiceStopProvenanceRecorder()
    service.sessionComponentBuilderProvider = fixture.forbidden("VPN graph")
    service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors")
    service.rootHelperManagerProvider = fixture.forbidden("root helper")
}

internal fun pausedServiceStopProvenanceRecorder(): RoomServiceStopProvenanceRecorder =
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

internal suspend fun awaitPausedServiceDestruction(service: Service) {
    val cleanupJob = checkNotNull(ReflectionHelpers.getField<Job?>(service, "destroyCleanupJob"))
    withTimeout(5_000) { cleanupJob.join() }
    assertTrue(cleanupJob.isCompleted)
    assertFalse(cleanupJob.isCancelled)
}

internal suspend fun attachPausedServiceSession(service: Service) {
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

internal fun skipPausedServiceInjection(service: Service) {
    // Unit construction supplies the shell explicitly; Full/Simple Hilt compilation verifies production injection.
    ReflectionHelpers.setField(service, "injected", true)
}

internal class PausedServiceReconstructionFixture(
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
