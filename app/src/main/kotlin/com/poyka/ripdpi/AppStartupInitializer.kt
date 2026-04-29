package com.poyka.ripdpi

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.detection.DetectionObservationStarter
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.services.CdnEchRefreshWorker
import com.poyka.ripdpi.services.CdnEchSeedFromCache
import com.poyka.ripdpi.services.DnsPathPreferenceInvalidator
import com.poyka.ripdpi.services.SharedPriorsRefreshWorker
import com.poyka.ripdpi.strategy.StrategyPackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppStartupInitializer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appCompatibilityResetter: AppCompatibilityResetter,
        private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
        private val detectionObservationStarter: DetectionObservationStarter,
        private val strategyPackService: StrategyPackService,
        private val dnsPathPreferenceInvalidator: DnsPathPreferenceInvalidator,
        private val cdnEchSeedFromCache: CdnEchSeedFromCache,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        fun initialize() {
            applicationScope.launch {
                val report = initializeSubsystems()
                Logger.i { report.toLogMessage() }
                runCatching {
                    detectionObservationStarter.startObserving(context, this)
                }.onFailure { error ->
                    Logger.w(error) { "App startup detection observation failed" }
                }
            }
        }

        internal suspend fun initializeSubsystems(): AppStartupReport {
            val compatibilityReset =
                runSubsystem(AppStartupSubsystem.CompatibilityReset) {
                    appCompatibilityResetter.resetIfNeeded()
                }
            val strategyPackInitialization =
                runSubsystem(AppStartupSubsystem.StrategyPackInitialization) {
                    strategyPackService.initialize()
                }
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
            return AppStartupReport(
                compatibilityReset = compatibilityReset,
                strategyPackInitialization = strategyPackInitialization,
                diagnosticsBootstrap = diagnosticsBootstrap,
                dnsPathInvalidatorRegistration = dnsPathInvalidatorRegistration,
                sharedPriorsWorkerEnqueue = sharedPriorsWorkerEnqueue,
                cdnEchSeed = cdnEchSeed,
                cdnEchWorkerEnqueue = cdnEchWorkerEnqueue,
            )
        }

        private suspend fun runSubsystem(
            subsystem: AppStartupSubsystem,
            action: suspend () -> Unit,
        ): AppStartupSubsystemResult {
            val failure =
                runCatching { action() }
                    .exceptionOrNull()
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

internal data class AppStartupReport(
    val compatibilityReset: AppStartupSubsystemResult,
    val strategyPackInitialization: AppStartupSubsystemResult,
    val diagnosticsBootstrap: AppStartupSubsystemResult,
    val dnsPathInvalidatorRegistration: AppStartupSubsystemResult,
    val sharedPriorsWorkerEnqueue: AppStartupSubsystemResult,
    val cdnEchSeed: AppStartupSubsystemResult,
    val cdnEchWorkerEnqueue: AppStartupSubsystemResult,
) {
    fun toLogMessage(): String =
        "App startup report: " +
            listOf(
                compatibilityReset,
                strategyPackInitialization,
                diagnosticsBootstrap,
                dnsPathInvalidatorRegistration,
                sharedPriorsWorkerEnqueue,
                cdnEchSeed,
                cdnEchWorkerEnqueue,
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
    CompatibilityReset("compatibility_reset"),
    StrategyPackInitialization("strategy_pack_initialization"),
    DiagnosticsBootstrap("diagnostics_bootstrap"),
    DnsPathInvalidatorRegistration("dns_path_invalidator_registration"),
    SharedPriorsRefreshWorkerEnqueue("shared_priors_refresh_worker_enqueue"),
    CdnEchSeedFromCache("cdn_ech_seed_from_cache"),
    CdnEchRefreshWorkerEnqueue("cdn_ech_refresh_worker_enqueue"),
}

internal enum class AppStartupSubsystemStatus {
    Succeeded,
    Failed,
}
