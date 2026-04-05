package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
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
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val diagnosticsArtifactReadStore: DiagnosticsArtifactReadStore,
        private val launcherProvider: Provider<AutomaticProbeLauncher>,
        @param:Named("automaticHandoverProbeDelayMs")
        private val automaticHandoverProbeDelayMs: Long,
        @param:Named("automaticHandoverProbeCooldownMs")
        private val automaticHandoverProbeCooldownMs: Long,
        @param:Named("automaticStrategyFailureProbeCooldownMs")
        private val automaticStrategyFailureProbeCooldownMs: Long,
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
            val isStrategyFailure =
                event.classification == AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE
            val settings = appSettingsRepository.snapshot()
            val launcher = launcherProvider.get()
            val effectiveCooldownMs =
                if (isStrategyFailure) automaticStrategyFailureProbeCooldownMs else automaticHandoverProbeCooldownMs
            val baseEligibility =
                AutomaticProbeCoordinator.evaluateBaseEligibility(
                    settings = settings,
                    event = event,
                    hasActiveScan = launcher.hasActiveScan(),
                    recentRuns = recentProbeRuns,
                    cooldownMs = effectiveCooldownMs,
                )
            if (baseEligibility is AutomaticProbeCoordinator.Eligibility.Rejected) return

            val rememberedPolicyBlocks =
                !isStrategyFailure &&
                    run {
                        val hasValidatedRememberedMatch =
                            rememberedNetworkPolicyStore.findValidatedMatch(
                                fingerprintHash = event.currentFingerprintHash,
                                mode = event.mode,
                            ) != null
                        AutomaticProbeCoordinator.evaluateRememberedPolicyEligibility(
                            hasValidatedRememberedMatch = hasValidatedRememberedMatch,
                        ) is AutomaticProbeCoordinator.Eligibility.Rejected
                    }
            if (rememberedPolicyBlocks) return

            val now = System.currentTimeMillis()
            val latestTelemetrySample =
                diagnosticsArtifactReadStore.getLatestTelemetrySampleForFingerprint(
                    activeMode = event.mode.name,
                    fingerprintHash = event.currentFingerprintHash,
                    createdAfter = now - AutomaticProbeCoordinator.recentFailureLookbackMs(),
                )
            val recentFailureEligibility =
                AutomaticProbeCoordinator.evaluateRecentFailureSignal(sample = latestTelemetrySample)
            if (recentFailureEligibility is AutomaticProbeCoordinator.Eligibility.Rejected) return

            if (launcher.launchAutomaticProbe(settings, event)) {
                recentProbeRuns[AutomaticProbeCoordinator.probeKey(event)] = now
            }
        }
    }
