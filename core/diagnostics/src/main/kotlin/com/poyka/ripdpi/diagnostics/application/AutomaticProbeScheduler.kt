@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
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
                        val initialDelayMs = activeProbeSafetyPolicy.automaticHandoverProbeDelayMs
                        var elapsedMs = initialDelayMs
                        delay(initialDelayMs)
                        while (policyHandoverEventStore.isPending(event.deliveryId)) {
                            val attempt = runCatching { processDelivery(event) }
                            val failure = attempt.exceptionOrNull()
                            if (failure is CancellationException || failure is Error) throw failure
                            if (attempt.getOrDefault(false)) return@launch
                            if (elapsedMs >= automaticProbeRetryWindowMs()) {
                                policyHandoverEventStore.acknowledge(event.deliveryId)
                                return@launch
                            }
                            val retryDelayMs = automaticProbeRetryDelayMs()
                            delay(retryDelayMs)
                            elapsedMs += retryDelayMs
                            if (!policyHandoverEventStore.isPending(event.deliveryId)) {
                                return@launch
                            }
                        }
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

        private suspend fun processDelivery(event: PolicyHandoverEvent): Boolean {
            var terminal = !policyHandoverEventStore.isPending(event.deliveryId)
            if (!terminal && launchIfEligible(event)) {
                if (policyHandoverEventStore.isPending(event.deliveryId)) {
                    policyHandoverEventStore.acknowledge(event.deliveryId)
                }
                terminal = true
            }
            return terminal
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
            var eligibility =
                AutomaticProbeCoordinator.evaluateBaseEligibility(
                    settings = settings,
                    event = event,
                    hasActiveScan = launcher.hasActiveScan(),
                    recentRuns = recentProbeRuns,
                    cooldownMs = activeProbeSafetyPolicy.cooldownMsForHandoverClassification(event.classification),
                )
            if (eligibility !is AutomaticProbeCoordinator.Eligibility.Rejected && !isStrategyFailure) {
                val hasValidatedRememberedMatch =
                    rememberedNetworkPolicyStore.findValidatedMatch(
                        fingerprintHash = event.currentFingerprintHash,
                        mode = event.mode,
                    ) != null
                eligibility =
                    AutomaticProbeCoordinator.evaluateRememberedPolicyEligibility(
                        hasValidatedRememberedMatch = hasValidatedRememberedMatch,
                    )
            }
            return if (eligibility is AutomaticProbeCoordinator.Eligibility.Rejected) {
                eligibility
            } else {
                val latestTelemetrySample =
                    diagnosticsArtifactReadStore.getLatestTelemetrySampleForFingerprint(
                        activeMode = event.mode.name,
                        fingerprintHash = event.currentFingerprintHash,
                        createdAfter = now - AutomaticProbeCoordinator.recentFailureLookbackMs(),
                    )
                AutomaticProbeCoordinator.evaluateRecentFailureSignal(sample = latestTelemetrySample)
            }
        }

        private fun automaticProbeRetryDelayMs(): Long =
            activeProbeSafetyPolicy.automaticHandoverProbeDelayMs.coerceAtLeast(1L)

        private fun automaticProbeRetryWindowMs(): Long = AutomaticProbeCoordinator.recentFailureLookbackMs()

        private companion object {
            val TransientRejectionReasons =
                setOf(
                    AutomaticProbeCoordinator.RejectionReason.ACTIVE_SCAN_RUNNING,
                    AutomaticProbeCoordinator.RejectionReason.COOLDOWN_ACTIVE,
                    AutomaticProbeCoordinator.RejectionReason.MISSING_RECENT_FAILURE_SIGNAL,
                )
        }
    }
