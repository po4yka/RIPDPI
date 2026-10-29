package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
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
 * finally block that also runs on cancellation. A request with a proxy config (a native scan
 * candidate) applies that config; any other request activates the [candidateProvider] candidate with
 * the same id. Fails the flow, without touching settings, when the config cannot be decoded, the
 * strategy id is unknown, or another strategy run holds [StrategyProbeRunGuard].
 */
class StrategyScopedProbeReplay(
    private val delegate: ProbeReplayService,
    private val candidateProvider: StrategyProbeCandidateProvider,
    private val activator: StrategyProbeActivator,
    private val proxyConfigApplier: ProxyConfigStrategyApplier,
    private val runGuard: StrategyProbeRunGuard,
) : ProbeReplayService {
    override fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent> =
        flow {
            check(runGuard.tryAcquire()) { "A strategy probe run is already active" }
            var restore: (suspend () -> Unit)? = null
            try {
                val proxyConfigJson = request.proxyConfigJson
                if (proxyConfigJson != null) {
                    val preferences =
                        checkNotNull(decodeRipDpiProxyUiPreferences(proxyConfigJson)) {
                            "Replay strategy config is invalid: ${request.strategyId}"
                        }
                    val snapshot = proxyConfigApplier.capture()
                    restore = { proxyConfigApplier.restore(snapshot) }
                    proxyConfigApplier.apply(preferences)
                } else {
                    val candidate =
                        checkNotNull(candidateProvider.listCandidates().firstOrNull { it.id == request.strategyId }) {
                            "Unknown replay strategy: ${request.strategyId}"
                        }
                    val snapshot = activator.capture()
                    restore = { activator.restore(snapshot) }
                    activator.activate(candidate)
                }
                emitAll(delegate.run(request))
            } finally {
                withContext(NonCancellable) { restore?.invoke() }
                runGuard.release()
            }
        }
}
