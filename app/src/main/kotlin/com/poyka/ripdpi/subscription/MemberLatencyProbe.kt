package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.ProfileQualityMeasurement
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyProfile

/** A measured latency for a selector member, or `null` when the member is unreachable. */
data class MemberLatency(
    val profileId: String,
    val latencyMillis: Long?,
    val quality: ProfileQualityMeasurement? = null,
    val reason: ProfileQualityReason = ProfileQualityReason.TransferFailed,
)

data class MemberProbeResult(
    val latencyMillis: Long?,
    val quality: ProfileQualityMeasurement? = null,
    val reason: ProfileQualityReason = ProfileQualityReason.Verified,
)

sealed interface MemberProbeTarget {
    data class Reachability(
        val url: String,
    ) : MemberProbeTarget

    data class Payload(
        val policy: PayloadFailoverPolicy,
    ) : MemberProbeTarget
}

/**
 * Measures a single selector member's reachability latency against the group's
 * `probeUrl`. Injectable so the prober is testable without a real network: the
 * production implementation verifies a complete bounded HTTP payload through the candidate relay.
 */
fun interface MemberLatencyProbe {
    /**
     * Returns the round-trip latency in milliseconds for [profile] probed against
     * [target], or `null` when the member did not respond.
     */
    suspend fun measure(
        profile: ProxyProfile,
        target: MemberProbeTarget,
    ): MemberProbeResult?
}

/**
 * Pure failover decision: the [MemberLatency] to switch to, or `null` when no
 * switch is warranted.
 *
 * - Only reachable members (non-`null` latency) are candidates.
 * - The lowest-latency reachable member wins.
 * - When a [currentProfileId] is set and reachable, the winner must beat it by
 *   **more than** [toleranceMs] — a candidate merely within the tolerance band
 *   does not justify churning the live exit.
 * - When the current selection is unset or unreachable, the lowest-latency
 *   reachable member is chosen outright (any working exit beats none).
 */
fun bestSwitchCandidate(
    latencies: List<MemberLatency>,
    currentProfileId: String?,
    toleranceMs: Int,
): String? {
    // Pair each reachable member with its measured (non-null) latency so the rest
    // of the decision needs no non-null assertions.
    val reachable = latencies.mapNotNull { entry -> entry.latencyMillis?.let { entry.profileId to it } }
    val best = reachable.minByOrNull { (_, latency) -> latency } ?: return null
    val (bestId, bestLatency) = best
    val currentLatency =
        currentProfileId
            ?.let { id -> reachable.firstOrNull { (profileId, _) -> profileId == id }?.second }
    return when {
        // Current selection is unset or unreachable — take the best working member.
        currentLatency == null -> bestId

        // Best must beat the current by strictly more than the tolerance band.
        bestLatency + toleranceMs < currentLatency -> bestId

        else -> null
    }
}
