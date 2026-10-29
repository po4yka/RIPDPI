package com.poyka.ripdpi.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Allows one run at a time that activates a strategy. A second activation would capture the first run's
 * candidate as the settings to restore. Callers fail at once instead of waiting.
 */
@Singleton
class StrategyProbeRunGuard
    @Inject
    constructor() {
        private val active = AtomicBoolean(false)

        fun tryAcquire(): Boolean = active.compareAndSet(false, true)

        fun release() {
            active.set(false)
        }
    }

/**
 * Ids [StrategyProbeActivator] can activate from [DefaultStrategyProbeCandidateProvider]. Scan results carry
 * native candidate ids, so only ids in this set can be replayed under their strategy.
 */
fun replayableStrategyCandidateIds(): Set<String> =
    builtInStrategyProbeCandidates()
        .filter(StrategyProbeCandidate::supportsTunEgressProbe)
        .mapTo(linkedSetOf(), StrategyProbeCandidate::id)
