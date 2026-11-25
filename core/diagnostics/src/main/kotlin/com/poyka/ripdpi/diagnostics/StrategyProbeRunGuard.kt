package com.poyka.ripdpi.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategyProbeRunGuard
    @Inject
    constructor() {
        private val active = AtomicBoolean(false)

        fun tryAcquire(): Boolean = active.compareAndSet(false, true)

        fun release() {
            active.set(false)
        }

        suspend fun releaseAfter(cleanup: suspend () -> Unit) {
            try {
                cleanup()
            } finally {
                release()
            }
        }
    }

fun replayableStrategyCandidateIds(): Set<String> =
    builtInStrategyProbeCandidates()
        .filter(StrategyProbeCandidate::supportsTunEgressProbe)
        .mapTo(linkedSetOf(), StrategyProbeCandidate::id)
