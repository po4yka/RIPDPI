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

class StrategyScopedProbeReplay(
    private val delegate: ProbeReplayService,
    private val candidateProvider: StrategyProbeCandidateProvider,
    private val activator: StrategyProbeActivator,
    private val proxyConfigApplier: ProxyConfigStrategyApplier,
    private val configSource: ScanCandidateConfigSource,
    private val runGuard: StrategyProbeRunGuard,
) : ProbeReplayService {
    override fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent> =
        flow {
            check(runGuard.tryAcquire()) { "A strategy probe run is already active" }
            var restore: (suspend () -> Unit)? = null
            try {
                restore = activate(request)
                emitAll(delegate.run(request))
            } finally {
                runGuard.releaseAfter { withContext(NonCancellable) { restore?.invoke() } }
            }
        }

    private suspend fun activate(request: ReplayProbeRequest): suspend () -> Unit {
        val sessionId = request.sessionId
        return if (sessionId != null) {
            val configJson = configSource.proxyConfigJson(sessionId, request.strategyId)
            checkNotNull(configJson) { "Replay strategy config not found: ${request.strategyId}" }
            val preferences =
                checkNotNull(decodeRipDpiProxyUiPreferences(configJson)) {
                    "Replay strategy config is invalid: ${request.strategyId}"
                }
            val snapshot = proxyConfigApplier.apply(preferences)
            suspend { proxyConfigApplier.restore(snapshot) }
        } else {
            val candidate =
                checkNotNull(candidateProvider.listCandidates().firstOrNull { it.id == request.strategyId }) {
                    "Unknown replay strategy: ${request.strategyId}"
                }
            val snapshot = activator.capture()
            val restore: suspend () -> Unit = { activator.restore(snapshot) }
            var activated = false
            try {
                activator.activate(candidate)
                activated = true
            } finally {
                if (!activated) withContext(NonCancellable) { restore() }
            }
            restore
        }
    }
}
