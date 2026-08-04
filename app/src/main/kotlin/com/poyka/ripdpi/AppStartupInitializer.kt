package com.poyka.ripdpi

import android.content.Context
import android.database.SQLException
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.backup.ResetEventName
import com.poyka.ripdpi.backup.ResetEventRecorder
import com.poyka.ripdpi.core.detection.DetectionObservationStarter
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsRetentionWorker
import com.poyka.ripdpi.seed.SimpleFlavorStartupHooks
import com.poyka.ripdpi.services.BootSessionRecorder
import com.poyka.ripdpi.services.CdnEchRefreshWorker
import com.poyka.ripdpi.services.CdnEchSeedFromCache
import com.poyka.ripdpi.services.DnsPathPreferenceInvalidator
import com.poyka.ripdpi.services.SharedPriorsRefreshWorker
import com.poyka.ripdpi.shortcuts.AppShortcutsPublisher
import com.poyka.ripdpi.strategy.StrategyPackService
import com.poyka.ripdpi.subscription.SubscriptionAutoUpdateWorker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class AppStartupReadinessState {
    Pending,
    Ready,
    Failed,
}

interface AppStartupReadiness {
    val readiness: StateFlow<AppStartupReadinessState>

    fun retryRecovery()
}

internal object ReadyAppStartupReadiness : AppStartupReadiness {
    override val readiness = MutableStateFlow(AppStartupReadinessState.Ready).asStateFlow()

    override fun retryRecovery() = Unit
}

@Singleton
class AppStartupInitializer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val startupDataRecovery: StartupDataRecovery,
        private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
        private val detectionObservationStarter: DetectionObservationStarter,
        private val strategyPackService: StrategyPackService,
        private val dnsPathPreferenceInvalidator: DnsPathPreferenceInvalidator,
        private val cdnEchSeedFromCache: CdnEchSeedFromCache,
        private val proxyGroupRepository: ProxyGroupRepository,
        private val bootSessionRecorder: BootSessionRecorder,
        private val appShortcutsPublisher: AppShortcutsPublisher,
        private val resetEventRecorder: ResetEventRecorder,
        private val startupDiagnosticsProbes: StartupDiagnosticsProbes,
        private val simpleFlavorStartupHooks: SimpleFlavorStartupHooks,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : AppStartupReadiness {
        private val readinessState = MutableStateFlow(AppStartupReadinessState.Pending)
        private val startupRunning = AtomicBoolean(false)
        override val readiness: StateFlow<AppStartupReadinessState> = readinessState.asStateFlow()

        fun initialize() {
            if (readinessState.value != AppStartupReadinessState.Pending) return
            launchStartup()
        }

        override fun retryRecovery() {
            if (!readinessState.compareAndSet(AppStartupReadinessState.Failed, AppStartupReadinessState.Pending)) {
                return
            }
            launchStartup()
        }

        private fun launchStartup() {
            if (!startupRunning.compareAndSet(false, true)) return
            applicationScope.launch {
                val startupRecovery =
                    try {
                        val recovered = recoverBeforeReadiness()
                        reconcileRemoteAcceptanceBeforeReadiness()
                        recovered
                    } catch (cancellation: CancellationException) {
                        startupRunning.set(false)
                        throw cancellation
                    } catch (error: IllegalStateException) {
                        // Release the single-flight guard before publishing Failed so
                        // an immediate UI retry cannot lose the retry launch.
                        startupRunning.set(false)
                        readinessState.value = AppStartupReadinessState.Failed
                        Logger.e(error) { "Pre-readiness recovery failed; startup remains gated" }
                        return@launch
                    }
                val report =
                    try {
                        initializeSubsystemsAfterRecovery(startupRecovery)
                    } catch (cancellation: CancellationException) {
                        startupRunning.set(false)
                        throw cancellation
                    } catch (error: IllegalStateException) {
                        startupRunning.set(false)
                        readinessState.value = AppStartupReadinessState.Failed
                        Logger.e(error) { "Required startup subsystem failed; startup remains gated" }
                        return@launch
                    }
                readinessState.value = AppStartupReadinessState.Ready
                try {
                    initializeAfterReadiness(report)
                } finally {
                    startupRunning.set(false)
                }
            }
        }

        private suspend fun recoverBeforeReadiness(): AppStartupRecovery =
            withContext(Dispatchers.IO) {
                AppStartupRecovery(
                    profileMutationRecovery = recoverProfileMutations(),
                    compatibilityReset = recoverCompatibilityReset(),
                )
            }

        private suspend fun initializeAfterReadiness(report: AppStartupReport) {
            runCatching { DiagnosticsRetentionWorker.enqueuePeriodic(context) }
                .onFailure { error -> Logger.w(error) { "Diagnostics retention worker failed to enqueue" } }
            runCatching { appShortcutsPublisher.start() }
                .onFailure { error -> Logger.w(error) { "App shortcuts publisher failed to start" } }
            runCatching { simpleFlavorStartupHooks.sessionWatcher.orElse(null)?.bind(applicationScope) }
                .onFailure { error -> Logger.w(error) { "Simple session watcher failed to bind" } }
            Logger.i { report.toLogMessage() }
            // Register Android 17 OOM/anomaly profiling triggers (no-op below
            // API 37). A captured heap dump is delivered to our result callback.
            runCatching {
                startupDiagnosticsProbes.memoryProfilingRegistrar.register()
            }.onFailure { error ->
                Logger.w(error) { "Memory profiling registration failed" }
            }
            runCatching {
                detectionObservationStarter.startObserving(context, applicationScope)
            }.onFailure { error ->
                Logger.w(error) { "App startup detection observation failed" }
            }
        }

        private suspend fun reconcileRemoteAcceptanceBeforeReadiness() {
            try {
                startupDiagnosticsProbes.remoteDeviceAcceptanceStartupReconciler.reconcilePendingRun()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SQLException) {
                Logger.w { startupRecoveryWarning(StartupRecoveryWarning.RemoteAcceptanceDatabase) }
            } catch (_: IllegalStateException) {
                Logger.w { startupRecoveryWarning(StartupRecoveryWarning.RemoteAcceptanceState) }
            }
        }

        private suspend fun recordRecentProcessExits() {
            // Record privacy-safe categories for recent process exits. This
            // diagnostics read is isolated so a failure never affects startup.
            try {
                startupDiagnosticsProbes.lastExitInspector.recordRecentProcessExits()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                Logger.w { startupRecoveryWarning(StartupRecoveryWarning.RecentProcessExit) }
            }
        }

        internal suspend fun initializeSubsystems(): AppStartupReport =
            initializeSubsystemsAfterRecovery(recoverBeforeReadiness())

        private suspend fun recoverProfileMutations(): AppStartupSubsystemResult {
            val profileMutationRecovery =
                runSubsystem(AppStartupSubsystem.ProfileMutationRecovery) {
                    startupDataRecovery.profileMutations.recover()
                }
            check(profileMutationRecovery.status == AppStartupSubsystemStatus.Succeeded) {
                "Profile mutation recovery failed"
            }
            return profileMutationRecovery
        }

        private suspend fun recoverCompatibilityReset(): AppStartupSubsystemResult =
            runSubsystem(AppStartupSubsystem.CompatibilityReset) {
                startupDataRecovery.appCompatibilityResetter.resetIfNeeded()
            }

        private suspend fun initializeSubsystemsAfterRecovery(startupRecovery: AppStartupRecovery): AppStartupReport {
            val simpleConfigSeed =
                runSubsystem(AppStartupSubsystem.SimpleConfigSeed) {
                    simpleFlavorStartupHooks.seeder.orElse(null)?.seed()
                }
            check(simpleConfigSeed.status == AppStartupSubsystemStatus.Succeeded) {
                "Required simple configuration seed failed"
            }
            val resetEventConsume =
                runSubsystem(AppStartupSubsystem.ResetEventConsume) {
                    // The reset wipe recorded this BEFORE deleting everything; it
                    // survived the wipe + ProcessPhoenix restart in a dedicated store.
                    // Consume it exactly once and surface it so diagnostics can tell a
                    // deliberate reset apart from a crash in this session's logs.
                    if (resetEventRecorder.consumeResetEvent()) {
                        Logger.i { "Diagnostics event: $ResetEventName (recovered across restart)" }
                    }
                }
            val strategyPackInitialization =
                runSubsystem(AppStartupSubsystem.StrategyPackInitialization) {
                    strategyPackService.initialize()
                }
            // Reconcile previous-process exits before runtime history starts collecting. Otherwise
            // the monitor can seal a root-cause assessment from the pre-reconciliation snapshot.
            recordRecentProcessExits()
            val diagnosticsBootstrap =
                runSubsystem(AppStartupSubsystem.DiagnosticsBootstrap) {
                    diagnosticsBootstrapperProvider.get().initialize()
                }
            val dnsPathInvalidatorRegistration =
                runSubsystem(AppStartupSubsystem.DnsPathInvalidatorRegistration) {
                    dnsPathPreferenceInvalidator.register()
                }
            val sharedPriorsWorkerEnqueue =
                runSubsystem(AppStartupSubsystem.SharedPriorsRefreshWorkerEnqueue) {
                    SharedPriorsRefreshWorker.enqueuePeriodic(context)
                }
            val cdnEchSeed =
                runSubsystem(AppStartupSubsystem.CdnEchSeedFromCache) {
                    cdnEchSeedFromCache.seedIfPresent()
                }
            val cdnEchWorkerEnqueue =
                runSubsystem(AppStartupSubsystem.CdnEchRefreshWorkerEnqueue) {
                    CdnEchRefreshWorker.enqueuePeriodic(context)
                }
            val subscriptionWorkerEnqueue =
                runSubsystem(AppStartupSubsystem.SubscriptionAutoUpdateWorkerEnqueue) {
                    SubscriptionAutoUpdateWorker.enqueuePeriodic(context, proxyGroupRepository.list())
                }
            val bootSessionRecorderRegistration =
                runSubsystem(AppStartupSubsystem.BootSessionRecorderRegistration) {
                    bootSessionRecorder.register()
                }
            return AppStartupReport(
                profileMutationRecovery = startupRecovery.profileMutationRecovery,
                resetEventConsume = resetEventConsume,
                compatibilityReset = startupRecovery.compatibilityReset,
                strategyPackInitialization = strategyPackInitialization,
                diagnosticsBootstrap = diagnosticsBootstrap,
                dnsPathInvalidatorRegistration = dnsPathInvalidatorRegistration,
                sharedPriorsWorkerEnqueue = sharedPriorsWorkerEnqueue,
                cdnEchSeed = cdnEchSeed,
                cdnEchWorkerEnqueue = cdnEchWorkerEnqueue,
                subscriptionWorkerEnqueue = subscriptionWorkerEnqueue,
                bootSessionRecorderRegistration = bootSessionRecorderRegistration,
                simpleConfigSeed = simpleConfigSeed,
            )
        }

        private suspend fun runSubsystem(
            subsystem: AppStartupSubsystem,
            action: suspend () -> Unit,
        ): AppStartupSubsystemResult {
            val failure =
                runCatching { action() }
                    .exceptionOrNull()
            if (failure is CancellationException) {
                throw failure
            }
            return if (failure == null) {
                Logger.i { "App startup subsystem succeeded: ${subsystem.logLabel}" }
                AppStartupSubsystemResult(
                    subsystem = subsystem,
                    status = AppStartupSubsystemStatus.Succeeded,
                )
            } else {
                Logger.w(failure) { "App startup subsystem failed: ${subsystem.logLabel}" }
                AppStartupSubsystemResult(
                    subsystem = subsystem,
                    status = AppStartupSubsystemStatus.Failed,
                    errorMessage = failure.message,
                )
            }
        }
    }

private data class AppStartupRecovery(
    val profileMutationRecovery: AppStartupSubsystemResult,
    val compatibilityReset: AppStartupSubsystemResult,
)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppStartupReadinessModule {
    @Binds
    abstract fun bindAppStartupReadiness(initializer: AppStartupInitializer): AppStartupReadiness
}

internal data class AppStartupReport(
    val profileMutationRecovery: AppStartupSubsystemResult,
    val resetEventConsume: AppStartupSubsystemResult,
    val compatibilityReset: AppStartupSubsystemResult,
    val strategyPackInitialization: AppStartupSubsystemResult,
    val diagnosticsBootstrap: AppStartupSubsystemResult,
    val dnsPathInvalidatorRegistration: AppStartupSubsystemResult,
    val sharedPriorsWorkerEnqueue: AppStartupSubsystemResult,
    val cdnEchSeed: AppStartupSubsystemResult,
    val cdnEchWorkerEnqueue: AppStartupSubsystemResult,
    val subscriptionWorkerEnqueue: AppStartupSubsystemResult,
    val bootSessionRecorderRegistration: AppStartupSubsystemResult,
    val simpleConfigSeed: AppStartupSubsystemResult,
) {
    fun toLogMessage(): String =
        "App startup report: " +
            listOf(
                profileMutationRecovery,
                resetEventConsume,
                compatibilityReset,
                strategyPackInitialization,
                diagnosticsBootstrap,
                dnsPathInvalidatorRegistration,
                sharedPriorsWorkerEnqueue,
                cdnEchSeed,
                cdnEchWorkerEnqueue,
                subscriptionWorkerEnqueue,
                bootSessionRecorderRegistration,
                simpleConfigSeed,
            ).joinToString(separator = ", ") { result ->
                buildString {
                    append(result.subsystem.logLabel)
                    append('=')
                    append(result.status.name.lowercase())
                    result.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                        append("(error=")
                        append(error)
                        append(')')
                    }
                }
            }
}

internal data class AppStartupSubsystemResult(
    val subsystem: AppStartupSubsystem,
    val status: AppStartupSubsystemStatus,
    val errorMessage: String? = null,
)

internal enum class AppStartupSubsystem(
    val logLabel: String,
) {
    ProfileMutationRecovery("profile_mutation_recovery"),
    ResetEventConsume("reset_event_consume"),
    CompatibilityReset("compatibility_reset"),
    StrategyPackInitialization("strategy_pack_initialization"),
    DiagnosticsBootstrap("diagnostics_bootstrap"),
    DnsPathInvalidatorRegistration("dns_path_invalidator_registration"),
    SharedPriorsRefreshWorkerEnqueue("shared_priors_refresh_worker_enqueue"),
    CdnEchSeedFromCache("cdn_ech_seed_from_cache"),
    CdnEchRefreshWorkerEnqueue("cdn_ech_refresh_worker_enqueue"),
    SubscriptionAutoUpdateWorkerEnqueue("subscription_auto_update_worker_enqueue"),
    BootSessionRecorderRegistration("boot_session_recorder_registration"),
    SimpleConfigSeed("simple_config_seed"),
}

internal enum class AppStartupSubsystemStatus {
    Succeeded,
    Failed,
}

internal enum class StartupRecoveryWarning {
    RemoteAcceptanceDatabase,
    RemoteAcceptanceState,
    RecentProcessExit,
}

internal fun startupRecoveryWarning(warning: StartupRecoveryWarning): String =
    when (warning) {
        StartupRecoveryWarning.RemoteAcceptanceDatabase -> {
            "Remote acceptance startup reconciliation failed: database"
        }

        StartupRecoveryWarning.RemoteAcceptanceState -> {
            "Remote acceptance startup reconciliation failed: state"
        }

        StartupRecoveryWarning.RecentProcessExit -> {
            "Recent process exit scan failed"
        }
    }
