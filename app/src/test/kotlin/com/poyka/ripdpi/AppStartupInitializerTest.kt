package com.poyka.ripdpi

import android.app.Application
import com.poyka.ripdpi.backup.ResetEventRecorder
import com.poyka.ripdpi.core.detection.DetectionObservationStarter
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.CdnEchPersistedCache
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PersistedEchEntry
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.exit.LastExitInspector
import com.poyka.ripdpi.diagnostics.profiling.MemoryProfilingRegistrar
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.seed.SimpleFlavorSeeder
import com.poyka.ripdpi.seed.SimpleFlavorSessionWatcher
import com.poyka.ripdpi.seed.SimpleFlavorStartupHooks
import com.poyka.ripdpi.services.BootSessionRecorder
import com.poyka.ripdpi.services.CdnEchSeedFromCache
import com.poyka.ripdpi.services.DnsPathPreferenceInvalidator
import com.poyka.ripdpi.services.FlowAppAttributionStore
import com.poyka.ripdpi.services.FlowAttribution
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceStartupReconciler
import com.poyka.ripdpi.shortcuts.AppShortcutsPublisher
import com.poyka.ripdpi.shortcuts.SelectorShortcutCapability
import com.poyka.ripdpi.strategy.StrategyPackService
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import com.poyka.ripdpi.testsupport.NoOpProfileMutationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Optional
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
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.bootSessionRecorderRegistration.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.simpleConfigSeed.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.simpleSessionWatcherBind.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(
                "App startup report: profile_mutation_recovery=succeeded, reset_event_consume=succeeded, " +
                    "compatibility_reset=succeeded, " +
                    "strategy_pack_initialization=succeeded, diagnostics_bootstrap=succeeded, " +
                    "dns_path_invalidator_registration=succeeded, " +
                    "shared_priors_refresh_worker_enqueue=succeeded, " +
                    "cdn_ech_seed_from_cache=succeeded, cdn_ech_refresh_worker_enqueue=succeeded, " +
                    "subscription_auto_update_worker_enqueue=succeeded, " +
                    "boot_session_recorder_registration=succeeded, " +
                    "simple_config_seed=succeeded, simple_session_watcher_bind=succeeded",
                report.toLogMessage(),
            )
        }

    @Test
    fun `initialize returns while recovery is suspended and gates downstream startup`() =
        runTest {
            val recoveryStarted = CompletableDeferred<Unit>()
            val releaseRecovery = CompletableDeferred<Unit>()
            val profileMutations =
                object : ProfileMutationCoordinator by NoOpProfileMutationCoordinator {
                    override suspend fun recover() {
                        recoveryStarted.complete(Unit)
                        releaseRecovery.await()
                    }
                }
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    startupOverrides = StartupTestOverrides(profileMutations = profileMutations),
                    scope = backgroundScope,
                )

            initializer.initialize()
            runCurrent()
            recoveryStarted.await()

            assertEquals(AppStartupReadinessState.Pending, initializer.readiness.value)
            assertEquals(0, compatibilityResetter.calls)
            assertEquals(0, strategyPackService.initializeCalls)
            assertEquals(0, diagnosticsBootstrapper.calls)

            releaseRecovery.complete(Unit)
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `recovery failure keeps startup gated and skips downstream subsystems`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    startupOverrides =
                        StartupTestOverrides(
                            profileMutations =
                                object : ProfileMutationCoordinator by NoOpProfileMutationCoordinator {
                                    override suspend fun recover() = error("synthetic recovery failure")
                                },
                        ),
                    scope = backgroundScope,
                )

            initializer.initialize()
            runCurrent()
            initializer.readiness.first { it == AppStartupReadinessState.Failed }
            runCurrent()

            assertEquals(0, compatibilityResetter.calls)
            assertEquals(0, strategyPackService.initializeCalls)
            assertEquals(0, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `failed recovery can be retried once without parallel startup work`() =
        runTest {
            var recoveryAttempts = 0
            val releaseRetry = CompletableDeferred<Unit>()
            val retryStarted = CompletableDeferred<Unit>()
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val profileMutations =
                object : ProfileMutationCoordinator by NoOpProfileMutationCoordinator {
                    override suspend fun recover() {
                        recoveryAttempts += 1
                        if (recoveryAttempts == 1) error("first recovery failed")
                        retryStarted.complete(Unit)
                        releaseRetry.await()
                    }
                }
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    startupOverrides = StartupTestOverrides(profileMutations = profileMutations),
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it == AppStartupReadinessState.Failed }

            initializer.retryRecovery()
            initializer.retryRecovery()
            retryStarted.await()

            assertEquals(AppStartupReadinessState.Pending, initializer.readiness.value)
            assertEquals(2, recoveryAttempts)
            assertEquals(0, compatibilityResetter.calls)

            releaseRetry.complete(Unit)
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(1, compatibilityResetter.calls)
            initializer.retryRecovery()
            runCurrent()
            assertEquals(2, recoveryAttempts)
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
            val lastExitInspector = RecordingLastExitInspector()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    detectionObservationStarter = detectionObservationStarter,
                    lastExitInspector = lastExitInspector,
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, lastExitInspector.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
            assertEquals(application, detectionObservationStarter.contexts.single())
        }

    @Test
    fun `ready observer cannot race diagnostics bootstrap ahead of exit reconciliation`() =
        runTest {
            val reconciliationStarted = CompletableDeferred<Unit>()
            val releaseReconciliation = CompletableDeferred<Unit>()
            val startupOrder = mutableListOf<String>()
            val diagnosticsBootstrapper =
                RecordingDiagnosticsBootstrapper(
                    onInitialize = { startupOrder += "runtime_history_bootstrap" },
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    lastExitInspector =
                        RecordingLastExitInspector(
                            onRecord = { startupOrder += "process_exit_reconciliation" },
                            started = reconciliationStarted,
                            release = releaseReconciliation,
                        ),
                    scope = backgroundScope,
                )
            backgroundScope.launch {
                initializer.readiness.first { it == AppStartupReadinessState.Ready }
                startupOrder += "ready_observed"
                diagnosticsBootstrapper.initialize()
            }

            initializer.initialize()
            reconciliationStarted.await()
            runCurrent()

            assertEquals(AppStartupReadinessState.Pending, initializer.readiness.value)
            assertEquals(listOf("process_exit_reconciliation"), startupOrder)
            assertEquals(0, diagnosticsBootstrapper.calls)

            releaseReconciliation.complete(Unit)
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(
                listOf(
                    "process_exit_reconciliation",
                    "runtime_history_bootstrap",
                    "ready_observed",
                    "runtime_history_bootstrap",
                ),
                startupOrder,
            )
            assertEquals(2, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `public initialize reconciles pending remote acceptance evidence`() =
        runTest {
            val callOrder = mutableListOf<String>()
            val reconciler = RecordingRemoteDeviceAcceptanceStartupReconciler { callOrder += "reconcile" }
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter { callOrder += "compatibility" },
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    remoteDeviceAcceptanceStartupReconciler = reconciler,
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(1, reconciler.calls)
            assertEquals(listOf("compatibility", "reconcile"), callOrder.take(2))
        }

    @Test
    fun `startup reconciliation completes before Ready can start a new acceptance run`() =
        runTest {
            val reconciliationStarted = CompletableDeferred<Unit>()
            val releaseReconciliation = CompletableDeferred<Unit>()
            val reconciler =
                RecordingRemoteDeviceAcceptanceStartupReconciler(
                    started = reconciliationStarted,
                    release = releaseReconciliation,
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    remoteDeviceAcceptanceStartupReconciler = reconciler,
                    scope = backgroundScope,
                )
            var newRunStarted = false
            backgroundScope.launch {
                initializer.readiness.first { it == AppStartupReadinessState.Ready }
                newRunStarted = true
            }

            initializer.initialize()
            runCurrent()
            reconciliationStarted.await()

            assertEquals(AppStartupReadinessState.Pending, initializer.readiness.value)
            assertFalse(newRunStarted)
            assertFalse(reconciler.completed)

            releaseReconciliation.complete(Unit)
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertTrue(reconciler.completed)
            assertTrue(newRunStarted)
        }

    @Test
    fun `process exit scan failure does not stop later startup probes`() =
        runTest {
            val lastExitInspector =
                RecordingLastExitInspector(
                    failure = IllegalStateException("exit-scan-boom"),
                )
            val detectionObservationStarter = RecordingDetectionObservationStarter()
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    detectionObservationStarter = detectionObservationStarter,
                    lastExitInspector = lastExitInspector,
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(1, lastExitInspector.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
        }

    @Test
    fun `process exit scan cancellation fails startup instead of leaving it pending`() =
        runTest {
            var inspectionCalls = 0
            val inspectionStarted = CompletableDeferred<Unit>()
            val lastExitInspector =
                object : LastExitInspector {
                    override suspend fun recordRecentProcessExits() {
                        inspectionCalls += 1
                        if (inspectionCalls == 1) {
                            inspectionStarted.complete(Unit)
                            throw CancellationException("secret-process-exit-canary")
                        }
                    }
                }
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val detectionObservationStarter = RecordingDetectionObservationStarter()
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    detectionObservationStarter = detectionObservationStarter,
                    lastExitInspector = lastExitInspector,
                    scope = backgroundScope,
                )

            initializer.initialize()
            inspectionStarted.await()
            runCurrent()

            assertEquals(AppStartupReadinessState.Failed, initializer.readiness.value)
            assertEquals(1, inspectionCalls)
            assertEquals(0, diagnosticsBootstrapper.calls)
            assertEquals(0, detectionObservationStarter.startCalls)

            initializer.retryRecovery()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(2, inspectionCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
        }

    @Test
    fun `unexpected recovery exception fails startup and remains retryable`() =
        runTest {
            var reconciliationCalls = 0
            val reconciliationStarted = CompletableDeferred<Unit>()
            val reconciler =
                object : RemoteDeviceAcceptanceStartupReconciler {
                    override suspend fun reconcilePendingRun() {
                        reconciliationCalls += 1
                        if (reconciliationCalls == 1) {
                            reconciliationStarted.complete(Unit)
                            throw UnsupportedOperationException("synthetic unexpected recovery failure")
                        }
                    }
                }
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    remoteDeviceAcceptanceStartupReconciler = reconciler,
                    scope = backgroundScope,
                )

            initializer.initialize()
            reconciliationStarted.await()
            runCurrent()

            assertEquals(AppStartupReadinessState.Failed, initializer.readiness.value)

            initializer.retryRecovery()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(2, reconciliationCalls)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppStartupInitializerFailureTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `startup recovery warnings never include throwable details`() {
        val canary = "secret-recovery-canary"

        StartupRecoveryWarning.entries.forEach { warning ->
            val message = startupRecoveryWarning(warning)
            assertFalse(message.contains(canary))
            assertFalse(message.contains("Throwable"))
            assertFalse(message.contains("Exception"))
        }
    }

    @Test
    fun `simple seed failure keeps startup gated and skips session watcher`() =
        runTest {
            var watcherBindCalls = 0
            val hooks =
                SimpleFlavorStartupHooks(
                    Optional.of(
                        object : SimpleFlavorSeeder {
                            override suspend fun seed() {
                                error("required-seed-failed")
                            }
                        },
                    ),
                    Optional.of(
                        object : SimpleFlavorSessionWatcher {
                            override fun bind(scope: CoroutineScope) {
                                watcherBindCalls++
                            }
                        },
                    ),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    startupOverrides = StartupTestOverrides(simpleFlavorStartupHooks = hooks),
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it != AppStartupReadinessState.Pending }
            runCurrent()

            assertEquals(AppStartupReadinessState.Failed, initializer.readiness.value)
            assertEquals(0, watcherBindCalls)
        }

    @Test
    fun `simple session watcher binds before startup readiness is published`() =
        runTest {
            var readinessAtBind: AppStartupReadinessState? = null
            lateinit var initializer: AppStartupInitializer
            val hooks =
                SimpleFlavorStartupHooks(
                    Optional.of(
                        object : SimpleFlavorSeeder {
                            override suspend fun seed() = Unit
                        },
                    ),
                    Optional.of(
                        object : SimpleFlavorSessionWatcher {
                            override fun bind(scope: CoroutineScope) {
                                readinessAtBind = initializer.readiness.value
                            }
                        },
                    ),
                )
            initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    startupOverrides = StartupTestOverrides(simpleFlavorStartupHooks = hooks),
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }

            assertEquals(AppStartupReadinessState.Pending, readinessAtBind)
        }

    @Test
    fun `simple session watcher failure keeps startup gated`() =
        runTest {
            val hooks =
                SimpleFlavorStartupHooks(
                    Optional.of(
                        object : SimpleFlavorSeeder {
                            override suspend fun seed() = Unit
                        },
                    ),
                    Optional.of(
                        object : SimpleFlavorSessionWatcher {
                            override fun bind(scope: CoroutineScope) {
                                error("required-watcher-bind-failed")
                            }
                        },
                    ),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    startupOverrides = StartupTestOverrides(simpleFlavorStartupHooks = hooks),
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it != AppStartupReadinessState.Pending }
            runCurrent()

            assertEquals(AppStartupReadinessState.Failed, initializer.readiness.value)
        }

    @Test
    fun `simple seed retry does not duplicate one-time startup registrations`() =
        runTest {
            var seedAttempts = 0
            var watcherBindCalls = 0
            val bootSessionRecorder = RecordingBootSessionRecorder()
            val dnsPathPreferenceInvalidator = RecordingDnsPathPreferenceInvalidator(application)
            val hooks =
                SimpleFlavorStartupHooks(
                    Optional.of(
                        object : SimpleFlavorSeeder {
                            override suspend fun seed() {
                                seedAttempts++
                                if (seedAttempts == 1) error("transient-seed-failure")
                            }
                        },
                    ),
                    Optional.of(
                        object : SimpleFlavorSessionWatcher {
                            override fun bind(scope: CoroutineScope) {
                                watcherBindCalls++
                            }
                        },
                    ),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = RecordingAppCompatibilityResetter(),
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    dnsPathPreferenceInvalidator = dnsPathPreferenceInvalidator,
                    bootSessionRecorder = bootSessionRecorder,
                    startupOverrides = StartupTestOverrides(simpleFlavorStartupHooks = hooks),
                    scope = backgroundScope,
                )

            initializer.initialize()
            initializer.readiness.first { it == AppStartupReadinessState.Failed }

            initializer.retryRecovery()
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
            runCurrent()

            assertEquals(2, seedAttempts)
            assertEquals(1, dnsPathPreferenceInvalidator.registerCalls)
            assertEquals(1, bootSessionRecorder.registerCalls)
            assertEquals(1, watcherBindCalls)
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
            initializer.readiness.first { it == AppStartupReadinessState.Ready }
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

    @Test
    fun `non-exception error from a subsystem is recorded failed and does not abort remaining subsystems`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            // An Error (not an Exception) must still be demoted to Failed, not propagated -- the
            // isolation boundary catches Throwable, not just Exception. CancellationException is the
            // sole Throwable that is intentionally rethrown, so this must NOT be one.
            val strategyPackService =
                RecordingStrategyPackService(
                    initializeFailure = AssertionError("strategy-error"),
                )
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            // Returns normally: if the catch were narrowed to Exception, the Error would propagate
            // out of initializeSubsystems() here and fail the test.
            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.strategyPackInitialization.status)
            assertEquals("strategy-error", report.strategyPackInitialization.errorMessage)
            // The next subsystem still ran -- the Error did not abort the loop.
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(1, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `two simultaneous subsystem failures are each recorded with distinct messages`() =
        runTest {
            val compatibilityResetter =
                RecordingAppCompatibilityResetter(failure = IllegalStateException("compat-boom"))
            val proxyGroupRepository =
                RecordingProxyGroupRepository(listFailure = IllegalStateException("subscription-list-boom"))
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = RecordingStrategyPackService(),
                    diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper(),
                    proxyGroupRepository = proxyGroupRepository,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Failed, report.compatibilityReset.status)
            assertEquals("compat-boom", report.compatibilityReset.errorMessage)
            assertEquals(AppStartupSubsystemStatus.Failed, report.subscriptionWorkerEnqueue.status)
            assertEquals("subscription-list-boom", report.subscriptionWorkerEnqueue.errorMessage)

            // Every subsystem between the two failures still ran.
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.dnsPathInvalidatorRegistration.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.sharedPriorsWorkerEnqueue.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.cdnEchSeed.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.cdnEchWorkerEnqueue.status)
            // ...and the loop continued past the SECOND failure to the trailing subsystem.
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.bootSessionRecorderRegistration.status)

            // Both distinct messages render in one log line; neither masks the other.
            val log = report.toLogMessage()
            assertTrue(log.contains("compatibility_reset=failed(error=compat-boom)"))
            assertTrue(log.contains("subscription_auto_update_worker_enqueue=failed(error=subscription-list-boom)"))
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, proxyGroupRepository.listCalls)
        }
}

private data class StartupTestOverrides(
    val profileMutations: ProfileMutationCoordinator = NoOpProfileMutationCoordinator,
    val simpleFlavorStartupHooks: SimpleFlavorStartupHooks =
        SimpleFlavorStartupHooks(Optional.empty(), Optional.empty()),
)

private fun createInitializer(
    compatibilityResetter: AppCompatibilityResetter,
    strategyPackService: StrategyPackService,
    diagnosticsBootstrapper: DiagnosticsBootstrapper,
    detectionObservationStarter: RecordingDetectionObservationStarter = RecordingDetectionObservationStarter(),
    application: Application = RuntimeEnvironment.getApplication(),
    dnsPathPreferenceInvalidator: RecordingDnsPathPreferenceInvalidator =
        RecordingDnsPathPreferenceInvalidator(application),
    proxyGroupRepository: ProxyGroupRepository = EmptyProxyGroupRepository,
    bootSessionRecorder: RecordingBootSessionRecorder = RecordingBootSessionRecorder(),
    resetEventRecorder: ResetEventRecorder = NoOpResetEventRecorder,
    lastExitInspector: LastExitInspector = NoOpLastExitInspector,
    memoryProfilingRegistrar: MemoryProfilingRegistrar = NoOpMemoryProfilingRegistrar,
    remoteDeviceAcceptanceStartupReconciler: RemoteDeviceAcceptanceStartupReconciler =
        NoOpRemoteDeviceAcceptanceStartupReconciler,
    startupOverrides: StartupTestOverrides = StartupTestOverrides(),
    scope: CoroutineScope,
): AppStartupInitializer =
    AppStartupInitializer(
        context = application,
        startupDataRecovery = StartupDataRecovery(compatibilityResetter, startupOverrides.profileMutations),
        diagnosticsBootstrapperProvider = constantProvider(diagnosticsBootstrapper),
        detectionObservationStarter = detectionObservationStarter,
        strategyPackService = strategyPackService,
        dnsPathPreferenceInvalidator = dnsPathPreferenceInvalidator,
        cdnEchSeedFromCache = CdnEchSeedFromCache(EmptyCdnEchPersistedCache),
        proxyGroupRepository = proxyGroupRepository,
        bootSessionRecorder = bootSessionRecorder,
        resetEventRecorder = resetEventRecorder,
        startupDiagnosticsProbes =
            StartupDiagnosticsProbes(
                lastExitInspector = lastExitInspector,
                memoryProfilingRegistrar = memoryProfilingRegistrar,
                remoteDeviceAcceptanceStartupReconciler = remoteDeviceAcceptanceStartupReconciler,
            ),
        simpleFlavorStartupHooks = startupOverrides.simpleFlavorStartupHooks,
        appShortcutsPublisher =
            AppShortcutsPublisher(
                context = application,
                proxyGroupRepository = proxyGroupRepository,
                selectorSelectionStore = NoOpSelectorSelectionStore,
                selectorShortcutCapability = SelectorShortcutCapability(application),
                applicationScope = scope,
            ),
        applicationScope = scope,
    )

private object NoOpLastExitInspector : LastExitInspector {
    override suspend fun recordRecentProcessExits() = Unit
}

private class RecordingLastExitInspector(
    private val failure: Throwable? = null,
    private val onRecord: (() -> Unit)? = null,
    private val started: CompletableDeferred<Unit>? = null,
    private val release: CompletableDeferred<Unit>? = null,
) : LastExitInspector {
    var calls: Int = 0
        private set

    override suspend fun recordRecentProcessExits() {
        calls += 1
        onRecord?.invoke()
        started?.complete(Unit)
        release?.await()
        failure?.let { throw it }
    }
}

private object NoOpMemoryProfilingRegistrar : MemoryProfilingRegistrar {
    override fun register() = Unit
}

private object NoOpRemoteDeviceAcceptanceStartupReconciler : RemoteDeviceAcceptanceStartupReconciler {
    override suspend fun reconcilePendingRun() = Unit
}

private class RecordingRemoteDeviceAcceptanceStartupReconciler(
    private val started: CompletableDeferred<Unit>? = null,
    private val release: CompletableDeferred<Unit>? = null,
    private val onReconcile: (() -> Unit)? = null,
) : RemoteDeviceAcceptanceStartupReconciler {
    var calls: Int = 0
        private set
    var completed: Boolean = false
        private set

    override suspend fun reconcilePendingRun() {
        calls += 1
        onReconcile?.invoke()
        started?.complete(Unit)
        release?.await()
        completed = true
    }
}

private object NoOpResetEventRecorder : ResetEventRecorder {
    override fun recordResetInitiated() = Unit

    override fun hasPendingResetEvent(): Boolean = false

    override fun consumeResetEvent(): Boolean = false
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
        flowAppAttributionStore = NoOpFlowAppAttributionStore,
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

private object NoOpAppSettingsRepository : AppSettingsRepository {
    override val settings: kotlinx.coroutines.flow.Flow<AppSettings> =
        kotlinx.coroutines.flow.flowOf(AppSettings.getDefaultInstance())

    override suspend fun snapshot(): AppSettings = AppSettings.getDefaultInstance()

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) = Unit

    override suspend fun replace(settings: AppSettings) = Unit
}

private object NoOpBootSessionStateStore : BootSessionStateStore {
    override fun lastSession(): BootSessionPointer? = null

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) = Unit

    override fun clear() = Unit

    override fun wasRunningAtUpdate(): Boolean = false

    override fun setWasRunningAtUpdate(value: Boolean) = Unit
}

private class RecordingBootSessionRecorder(
    private val failure: Throwable? = null,
    private val onRegister: (() -> Unit)? = null,
) : BootSessionRecorder(
        serviceStateStore = FakeServiceStateStore(),
        appSettingsRepository = NoOpAppSettingsRepository,
        bootSessionStateStore = NoOpBootSessionStateStore,
        appScope = CoroutineScope(SupervisorJob()),
    ) {
    var registerCalls: Int = 0
        private set

    override fun register() {
        registerCalls += 1
        onRegister?.invoke()
        failure?.let { throw it }
    }
}

private object NoOpNetworkDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    override suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate? = null

    override suspend fun clearAll() {
        // no-op
    }

    override suspend fun deleteForFingerprint(fingerprintHash: String) {
        // no-op
    }

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity = error("not used in startup tests")
}

private object NoOpFlowAppAttributionStore : FlowAppAttributionStore {
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
    ): Int = -1

    override fun lookup(ipSetDigest: String): FlowAttribution.Attributed? = null

    override fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    ) = Unit

    override fun clear() = Unit
}
