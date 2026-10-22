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
    ): String? {
        results.forEach { result ->
            successes[result.profileId] =
                if (result.latencyMillis ==
                    null
                ) {
                    0
                } else {
                    ((successes[result.profileId] ?: 0) + 1).coerceAtMost(policy.recoveryThreshold)
                }
        }
        val activeHealthy = results.any { it.profileId == current && it.latencyMillis != null }
        failures = if (activeHealthy) 0 else (failures + 1).coerceAtMost(policy.failureThreshold)
        val anyHealthy = results.any { it.latencyMillis != null }
        outagePasses = if (anyHealthy) 0 else (outagePasses + 1).coerceAtMost(MaxBackoffExponent)
        val waitSeconds =
            (policy.intervalSeconds.toLong() * (1L shl outagePasses)).coerceAtMost(policy.maxBackoffSeconds.toLong())
        nextProbeAt = now + waitSeconds * MillisecondsPerSecond
        if (activeHealthy || failures < policy.failureThreshold || now < cooldownUntil) return null
        return results
            .firstOrNull {
                it.profileId != current && (successes[it.profileId] ?: 0) >= policy.recoveryThreshold
            }?.profileId
    }

    fun switched(now: Long) {
        cooldownUntil = now + policy.cooldownSeconds * MillisecondsPerSecond
        failures = 0
        successes.clear()
    }
}

private const val MillisecondsPerSecond = 1_000L
private const val MaxBackoffExponent = 10
