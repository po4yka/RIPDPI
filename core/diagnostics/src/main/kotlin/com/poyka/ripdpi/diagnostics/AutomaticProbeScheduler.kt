package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

interface AutomaticProbeLauncher {
    fun hasActiveScan(): Boolean

    suspend fun launchAutomaticProbe(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: PolicyHandoverEvent,
    ): Boolean
}

@Singleton
class AutomaticProbeScheduler
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val launcherProvider: Provider<AutomaticProbeLauncher>,
        @param:Named("automaticHandoverProbeDelayMs")
        private val automaticHandoverProbeDelayMs: Long,
        @param:Named("automaticHandoverProbeCooldownMs")
        private val automaticHandoverProbeCooldownMs: Long,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) {
        private val pendingProbeJobs = ConcurrentHashMap<Mode, Job>()
        private val recentProbeRuns = ConcurrentHashMap<String, Long>()

        fun schedule(event: PolicyHandoverEvent) {
            pendingProbeJobs[event.mode]?.cancel()
            pendingProbeJobs[event.mode] =
                scope.launch {
                    delay(automaticHandoverProbeDelayMs)
                    launchIfEligible(event)
                }
        }

        private suspend fun launchIfEligible(event: PolicyHandoverEvent) {
            val settings = appSettingsRepository.snapshot()
            val launcher = launcherProvider.get()
            if (
                !AutomaticProbeCoordinator.shouldLaunchProbe(
                    settings = settings,
                    event = event,
                    hasActiveScan = launcher.hasActiveScan(),
                    recentRuns = recentProbeRuns,
                    cooldownMs = automaticHandoverProbeCooldownMs,
                )
            ) {
                return
            }
            if (launcher.launchAutomaticProbe(settings, event)) {
                recentProbeRuns[AutomaticProbeCoordinator.probeKey(event)] = System.currentTimeMillis()
            }
        }
    }
