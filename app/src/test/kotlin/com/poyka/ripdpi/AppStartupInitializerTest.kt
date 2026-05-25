package com.poyka.ripdpi

import android.app.Application
import com.poyka.ripdpi.core.detection.DetectionObservationStarter
import com.poyka.ripdpi.data.CdnEchPersistedCache
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PersistedEchEntry
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.services.CdnEchSeedFromCache
import com.poyka.ripdpi.services.DnsPathPreferenceInvalidator
import com.poyka.ripdpi.shortcuts.AppShortcutsPublisher
import com.poyka.ripdpi.strategy.StrategyPackService
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppStartupInitializerTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `startup report marks all subsystems succeeded when initialization succeeds`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.dnsPathInvalidatorRegistration.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.sharedPriorsWorkerEnqueue.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.cdnEchSeed.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.cdnEchWorkerEnqueue.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.subscriptionWorkerEnqueue.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(
                "App startup report: compatibility_reset=succeeded, " +
                    "strategy_pack_initialization=succeeded, diagnostics_bootstrap=succeeded, " +
                    "dns_path_invalidator_registration=succeeded, " +
                    "shared_priors_refresh_worker_enqueue=succeeded, " +
                    "cdn_ech_seed_from_cache=succeeded, cdn_ech_refresh_worker_enqueue=succeeded, " +
                    "subscription_auto_update_worker_enqueue=succeeded",
                report.toLogMessage(),
            )
        }

    @Test
    fun `compatibility reset failure does not prevent strategy pack or diagnostics startup`() =
        runTest {
            val compatibilityResetter =
                RecordingAppCompatibilityResetter(
                    failure = IllegalStateException("compat-boom"),
                )
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Failed, report.compatibilityReset.status)
            assertEquals("compat-boom", report.compatibilityReset.errorMessage)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertTrue(report.toLogMessage().contains("compatibility_reset=failed(error=compat-boom)"))
        }

    @Test
    fun `strategy pack initialization failure does not prevent diagnostics startup`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService =
                RecordingStrategyPackService(
                    initializeFailure = IllegalStateException("strategy-boom"),
                )
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.strategyPackInitialization.status)
            assertEquals("strategy-boom", report.strategyPackInitialization.errorMessage)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `diagnostics bootstrap failure does not prevent earlier startup steps`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper =
                RecordingDiagnosticsBootstrapper(
                    failure = IllegalStateException("diagnostics-boom"),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.diagnosticsBootstrap.status)
            assertEquals("diagnostics-boom", report.diagnosticsBootstrap.errorMessage)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `public initialize still starts detection observation when a subsystem fails`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService =
                RecordingStrategyPackService(
                    initializeFailure = IllegalStateException("strategy-boom"),
                )
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val detectionObservationStarter = RecordingDetectionObservationStarter()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    detectionObservationStarter = detectionObservationStarter,
                    scope = backgroundScope,
                )

            initializer.initialize()
            yield()
            runCurrent()

            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
            assertEquals(application, detectionObservationStarter.contexts.single())
        }

    @Test
    fun `detection observation failure is swallowed after successful subsystem initialization`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val detectionObservationStarter =
                RecordingDetectionObservationStarter(
                    failure = IllegalStateException("detect-boom"),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    detectionObservationStarter = detectionObservationStarter,
                    scope = backgroundScope,
                )

            initializer.initialize()
            yield()
            runCurrent()

            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
        }

    @Test
    fun `initializeSubsystems preserves subsystem ordering`() =
        runTest {
            val callOrder = mutableListOf<String>()
            val initializer =
                createInitializer(
                    compatibilityResetter =
                        RecordingAppCompatibilityResetter(onReset = { callOrder += "compatibility" }),
                    strategyPackService =
                        RecordingStrategyPackService(onInitialize = { callOrder += "strategy" }),
                    diagnosticsBootstrapper =
                        RecordingDiagnosticsBootstrapper(onInitialize = { callOrder += "diagnostics" }),
                    dnsPathPreferenceInvalidator =
                        RecordingDnsPathPreferenceInvalidator(
                            application = application,
                            onRegister = { callOrder += "dns_path_invalidator" },
                        ),
                    scope = backgroundScope,
                )

            initializer.initializeSubsystems()

            assertEquals(listOf("compatibility", "strategy", "diagnostics", "dns_path_invalidator"), callOrder)
        }

    @Test
    fun `initializeSubsystems registers DNS path invalidator`() =
        runTest {
            val invalidator = RecordingDnsPathPreferenceInvalidator(application = application)
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    dnsPathPreferenceInvalidator = invalidator,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(1, invalidator.registerCalls)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.dnsPathInvalidatorRegistration.status)
        }

    @Test
    fun `dns path invalidator failure does not prevent earlier startup steps`() =
        runTest {
            val invalidator =
                RecordingDnsPathPreferenceInvalidator(
                    application = application,
                    failure = IllegalStateException("dns-invalidator-boom"),
                )
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    dnsPathPreferenceInvalidator = invalidator,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.dnsPathInvalidatorRegistration.status)
            assertEquals("dns-invalidator-boom", report.dnsPathInvalidatorRegistration.errorMessage)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, invalidator.registerCalls)
        }

    @Test
    fun `startup cancellation propagates and stops remaining subsystems`() =
        runTest {
            val compatibilityResetter =
                RecordingAppCompatibilityResetter(
                    failure = CancellationException("startup-cancelled"),
                )
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val thrown =
                try {
                    initializer.initializeSubsystems()
                    null
                } catch (error: CancellationException) {
                    error
                }

            assertEquals("startup-cancelled", thrown?.message)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(0, strategyPackService.initializeCalls)
            assertEquals(0, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `subscription worker enqueue failure is isolated after preceding startup steps`() =
        runTest {
            val proxyGroupRepository =
                RecordingProxyGroupRepository(
                    listFailure = IllegalStateException("subscription-list-boom"),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    proxyGroupRepository = proxyGroupRepository,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.cdnEchSeed.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.subscriptionWorkerEnqueue.status)
            assertEquals("subscription-list-boom", report.subscriptionWorkerEnqueue.errorMessage)
            assertEquals(1, proxyGroupRepository.listCalls)
        }

    private fun createInitializer(
        compatibilityResetter: AppCompatibilityResetter,
        strategyPackService: StrategyPackService,
        diagnosticsBootstrapper: DiagnosticsBootstrapper,
        detectionObservationStarter: RecordingDetectionObservationStarter = RecordingDetectionObservationStarter(),
        dnsPathPreferenceInvalidator: RecordingDnsPathPreferenceInvalidator =
            RecordingDnsPathPreferenceInvalidator(application),
        proxyGroupRepository: ProxyGroupRepository = EmptyProxyGroupRepository,
        scope: CoroutineScope,
    ): AppStartupInitializer =
        AppStartupInitializer(
            context = application,
            appCompatibilityResetter = compatibilityResetter,
            diagnosticsBootstrapperProvider = constantProvider(diagnosticsBootstrapper),
            detectionObservationStarter = detectionObservationStarter,
            strategyPackService = strategyPackService,
            dnsPathPreferenceInvalidator = dnsPathPreferenceInvalidator,
            cdnEchSeedFromCache = CdnEchSeedFromCache(EmptyCdnEchPersistedCache),
            proxyGroupRepository = proxyGroupRepository,
            appShortcutsPublisher =
                AppShortcutsPublisher(
                    context = application,
                    serviceStateStore = FakeServiceStateStore(),
                    proxyGroupRepository = proxyGroupRepository,
                    selectorSelectionStore = NoOpSelectorSelectionStore,
                    applicationScope = scope,
                ),
            applicationScope = scope,
        )
}

private object NoOpSelectorSelectionStore : SelectorSelectionStore {
    override fun selectedProfileId(groupId: String): StateFlow<String?> = MutableStateFlow(null)

    override fun select(
        groupId: String,
        profileId: String,
    ) = Unit

    override fun clearSelection(groupId: String) = Unit
}

private object EmptyProxyGroupRepository : ProxyGroupRepository {
    override suspend fun add(group: ProxyGroup) = Unit

    override suspend fun update(group: ProxyGroup) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun list(): List<ProxyGroup> = emptyList()

    override fun groups(): kotlinx.coroutines.flow.Flow<List<ProxyGroup>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

private class RecordingProxyGroupRepository(
    private val listFailure: Throwable? = null,
) : ProxyGroupRepository {
    var listCalls: Int = 0
        private set

    override suspend fun add(group: ProxyGroup) = Unit

    override suspend fun update(group: ProxyGroup) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun list(): List<ProxyGroup> {
        listCalls += 1
        listFailure?.let { throw it }
        return emptyList()
    }

    override fun groups(): kotlinx.coroutines.flow.Flow<List<ProxyGroup>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

private object EmptyCdnEchPersistedCache : CdnEchPersistedCache {
    override suspend fun load(): PersistedEchEntry? = null

    override suspend fun save(entry: PersistedEchEntry) = Unit

    override suspend fun clear() = Unit
}

private class RecordingAppCompatibilityResetter(
    private val failure: Throwable? = null,
    private val onReset: (() -> Unit)? = null,
) : AppCompatibilityResetter {
    var calls: Int = 0
        private set

    override fun resetIfNeeded() {
        calls += 1
        onReset?.invoke()
        failure?.let { throw it }
    }
}

private class RecordingStrategyPackService(
    private val initializeFailure: Throwable? = null,
    private val onInitialize: (() -> Unit)? = null,
) : StrategyPackService {
    var initializeCalls: Int = 0
        private set

    override fun initialize() {
        initializeCalls += 1
        onInitialize?.invoke()
        initializeFailure?.let { throw it }
    }

    override suspend fun refreshNow() {
        error("unused")
    }
}

private class RecordingDiagnosticsBootstrapper(
    private val failure: Throwable? = null,
    private val onInitialize: (() -> Unit)? = null,
) : DiagnosticsBootstrapper {
    var calls: Int = 0
        private set

    override suspend fun initialize() {
        calls += 1
        onInitialize?.invoke()
        failure?.let { throw it }
    }
}

private class RecordingDetectionObservationStarter(
    private val failure: Throwable? = null,
) : DetectionObservationStarter {
    var startCalls: Int = 0
        private set
    val contexts = mutableListOf<Application>()

    override fun startObserving(
        context: android.content.Context,
        scope: CoroutineScope,
    ) {
        startCalls += 1
        contexts += context as Application
        failure?.let { throw it }
    }
}

private fun constantProvider(bootstrapper: DiagnosticsBootstrapper): Provider<DiagnosticsBootstrapper> =
    object : Provider<DiagnosticsBootstrapper> {
        override fun get(): DiagnosticsBootstrapper = bootstrapper
    }

private class RecordingDnsPathPreferenceInvalidator(
    application: Application,
    private val failure: Throwable? = null,
    private val onRegister: (() -> Unit)? = null,
) : DnsPathPreferenceInvalidator(
        context = application,
        networkDnsPathPreferenceStore = NoOpNetworkDnsPathPreferenceStore,
        appScope = CoroutineScope(SupervisorJob()),
        trackedPackages = emptySet(),
    ) {
    var registerCalls: Int = 0
        private set

    override fun register() {
        registerCalls += 1
        onRegister?.invoke()
        failure?.let { throw it }
    }

    override fun unregister() {
        // No-op for tests; the real receiver was never registered.
    }
}

private object NoOpNetworkDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    override suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate? = null

    override suspend fun clearAll() {
        // no-op
    }

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity = error("not used in startup tests")
}
