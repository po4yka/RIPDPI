package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy

/** Transient evidence only. The caller discards it on policy, network, or manual-selection changes. */
internal class PayloadFailoverState(
    private val policy: PayloadFailoverPolicy,
) {
    private val successes = mutableMapOf<String, Int>()
    private var failures = 0
    private var outagePasses = 0
    private var cooldownUntil = Long.MIN_VALUE
    var nextProbeAt: Long = Long.MIN_VALUE
        private set

    fun observe(
        results: List<MemberLatency>,
        current: String?,
        now: Long,
        allCandidatesObserved: Boolean = true,
    ): String? {
        results.forEach { result ->
            val previous = successes[result.profileId] ?: 0
            successes[result.profileId] =
                if (result.latencyMillis != null) {
                    (previous + 1).coerceAtMost(policy.recoveryThreshold)
                } else {
                    0
                }
        }
        val activeHealthy = results.any { it.profileId == current && it.latencyMillis != null }
        if (activeHealthy) successes.clear()
        failures = if (activeHealthy) 0 else (failures + 1).coerceAtMost(policy.failureThreshold)
        val anyHealthy = results.any { it.latencyMillis != null }
        outagePasses =
            when {
                anyHealthy -> 0
                allCandidatesObserved -> (outagePasses + 1).coerceAtMost(MaxBackoffExponent)
                else -> outagePasses
            }
        val waitSeconds =
            (policy.intervalSeconds.toLong() * (1L shl outagePasses)).coerceAtMost(policy.maxBackoffSeconds.toLong())
        nextProbeAt = now + waitSeconds * MillisecondsPerSecond
        if (activeHealthy || failures < policy.failureThreshold || now < cooldownUntil) return null
        return results
            .firstOrNull {
                it.profileId != current && (successes[it.profileId] ?: 0) >= policy.recoveryThreshold
            }?.profileId
    }

    /** Incomplete rounds cannot extend a consecutive evidence streak. */
    fun discardObservation() {
        failures = 0
        successes.clear()
    }

    fun switched(now: Long) {
        cooldownUntil = now + policy.cooldownSeconds * MillisecondsPerSecond
        failures = 0
        successes.clear()
    }
}

private const val MillisecondsPerSecond = 1_000L
private const val MaxBackoffExponent = 10
