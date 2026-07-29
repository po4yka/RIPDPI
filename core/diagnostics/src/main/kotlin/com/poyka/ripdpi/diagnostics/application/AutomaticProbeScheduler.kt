@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    /** Returns true only when this delivery already has a durable terminal scan row and may be acknowledged. */
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
        private val diagnosticsArtifactReadStore: DiagnosticsArtifactQueryStore,
        private val policyHandoverEventStore: PolicyHandoverEventStore,
        private val launcherProvider: Provider<AutomaticProbeLauncher>,
        private val activeProbeSafetyPolicy: ActiveProbeSafetyPolicy,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) {
        private val pendingProbeJobs = ConcurrentHashMap<String, Job>()
        private val recentProbeRuns = ConcurrentHashMap<String, Long>()

        fun schedule(event: PolicyHandoverEvent) {
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        delay(activeProbeSafetyPolicy.automaticHandoverProbeDelayMs)
                        while (!launchIfEligible(event)) {
                            delay(automaticProbeRetryDelayMs())
                        }
                        policyHandoverEventStore.acknowledge(event.deliveryId)
                    } finally {
                        pendingProbeJobs.remove(event.deliveryId)
                    }
                }
            if (pendingProbeJobs.putIfAbsent(event.deliveryId, job) == null) {
                job.start()
            } else {
                job.cancel()
            }
        }

        private suspend fun launchIfEligible(event: PolicyHandoverEvent): Boolean {
            val isStrategyFailure =
                event.classification == AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE
            val settings = appSettingsRepository.snapshot()
            val launcher = launcherProvider.get()
            val now = System.currentTimeMillis()
            val eligibility = evaluateEligibility(event, settings, launcher, isStrategyFailure, now)
            if (eligibility is AutomaticProbeCoordinator.Eligibility.Rejected) {
                return eligibility.reason !in TransientRejectionReasons
            }
            val launched = launcher.launchAutomaticProbe(settings, event)
            if (launched) {
                recentProbeRuns[AutomaticProbeCoordinator.probeKey(event)] = now
            }
            return launched
        }

        private suspend fun evaluateEligibility(
            event: PolicyHandoverEvent,
            settings: com.poyka.ripdpi.proto.AppSettings,
            launcher: AutomaticProbeLauncher,
            isStrategyFailure: Boolean,
            now: Long,
        ): AutomaticProbeCoordinator.Eligibility {
            val baseEligibility =
                AutomaticProbeCoordinator.evaluateBaseEligibility(
                    settings = settings,
                    event = event,
                    hasActiveScan = launcher.hasActiveScan(),
                    recentRuns = recentProbeRuns,
                    cooldownMs = activeProbeSafetyPolicy.cooldownMsForHandoverClassification(event.classification),
                )
            if (baseEligibility is AutomaticProbeCoordinator.Eligibility.Rejected) return baseEligibility
            if (!isStrategyFailure) {
                val hasValidatedRememberedMatch =
                    rememberedNetworkPolicyStore.findValidatedMatch(
                        fingerprintHash = event.currentFingerprintHash,
                        mode = event.mode,
                    ) != null
                val rememberedPolicyEligibility =
                    AutomaticProbeCoordinator.evaluateRememberedPolicyEligibility(
                        hasValidatedRememberedMatch = hasValidatedRememberedMatch,
                    )
                if (rememberedPolicyEligibility is AutomaticProbeCoordinator.Eligibility.Rejected) {
                    return rememberedPolicyEligibility
                }
            }
            val latestTelemetrySample =
                diagnosticsArtifactReadStore.getLatestTelemetrySampleForFingerprint(
                    activeMode = event.mode.name,
                    fingerprintHash = event.currentFingerprintHash,
                    createdAfter = now - AutomaticProbeCoordinator.recentFailureLookbackMs(),
                )
            return AutomaticProbeCoordinator.evaluateRecentFailureSignal(sample = latestTelemetrySample)
        }

        private fun automaticProbeRetryDelayMs(): Long =
            activeProbeSafetyPolicy.automaticHandoverProbeDelayMs.coerceAtLeast(1L)

        private companion object {
            val TransientRejectionReasons =
                setOf(
                    AutomaticProbeCoordinator.RejectionReason.ACTIVE_SCAN_RUNNING,
                    AutomaticProbeCoordinator.RejectionReason.COOLDOWN_ACTIVE,
                    AutomaticProbeCoordinator.RejectionReason.MISSING_RECENT_FAILURE_SIGNAL,
                )
        }
    }
