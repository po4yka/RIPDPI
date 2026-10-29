package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.diagnostics.StrategyProbeActivationSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeActivator
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeRunGuard
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Runs [delegate] with the request's strategy active: capture, activate, run, then restore in a
 * finally block that also runs on cancellation. Fails the flow, without touching settings, when the
 * strategy id is unknown or another strategy run holds [StrategyProbeRunGuard].
 */
class StrategyScopedProbeReplay(
    private val delegate: ProbeReplayService,
    private val candidateProvider: StrategyProbeCandidateProvider,
    private val activator: StrategyProbeActivator,
    private val runGuard: StrategyProbeRunGuard,
) : ProbeReplayService {
    override fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent> =
        flow {
            check(runGuard.tryAcquire()) { "A strategy probe run is already active" }
            var snapshot: StrategyProbeActivationSnapshot? = null
            try {
                val candidate =
                    checkNotNull(candidateProvider.listCandidates().firstOrNull { it.id == request.strategyId }) {
                        "Unknown replay strategy: ${request.strategyId}"
                    }
                snapshot = activator.capture()
                activator.activate(candidate)
                emitAll(delegate.run(request))
            } finally {
                withContext(NonCancellable) { snapshot?.let { activator.restore(it) } }
                runGuard.release()
            }
        }
}
