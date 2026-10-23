package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityMeasurement
import com.poyka.ripdpi.data.ProfileQualityPolicy

/** Consecutive comparable facts only; the existing failover owner retains selection authority. */
internal class PayloadQualityEvidence {
    private var challengerId: String? = null
    private var wins = 0

    fun discard() {
        challengerId = null
        wins = 0
    }

    fun observe(
        results: List<MemberLatency>,
        current: String?,
        policy: ProfileQualityPolicy,
        threshold: Int,
        inCooldown: Boolean,
    ): QualityDecision {
        val active = results.firstOrNull { it.profileId == current }
        val challenger = results.firstOrNull { it.profileId != current }
        val reason = improvement(active?.quality, challenger?.quality, policy)
        if (reason == null || challenger == null || inCooldown) {
            discard()
            return QualityDecision(
                challenger?.profileId,
                if (inCooldown) {
                    ProfileQualityDecisionReason.Cooldown
                } else {
                    ProfileQualityDecisionReason.NoQualifiedImprovement
                },
            )
        }
        wins = if (challengerId == challenger.profileId) wins + 1 else 1
        challengerId = challenger.profileId
        return QualityDecision(
            challenger.profileId,
            if (wins >= threshold) reason else ProfileQualityDecisionReason.CollectingEvidence,
            qualified = true,
        )
    }
}

internal data class QualityDecision(
    val challengerId: String?,
    val reason: ProfileQualityDecisionReason,
    val qualified: Boolean = false,
) {
    val winner: String?
        get() =
            challengerId.takeIf {
                reason == ProfileQualityDecisionReason.PromotedForGoodput ||
                    reason == ProfileQualityDecisionReason.PromotedForLoadedLatency
            }
}

private fun improvement(
    active: ProfileQualityMeasurement?,
    reserve: ProfileQualityMeasurement?,
    policy: ProfileQualityPolicy,
): ProfileQualityDecisionReason? {
    if (active == null || reserve == null) return null
    val sameObject = active.probeIndex == reserve.probeIndex && active.verifiedBytes == reserve.verifiedBytes
    return if (active.complete && reserve.complete &&
        sameObject
    ) {
        qualifiedImprovement(active, reserve, policy)
    } else {
        null
    }
}

private fun qualifiedImprovement(
    active: ProfileQualityMeasurement,
    reserve: ProfileQualityMeasurement,
    policy: ProfileQualityPolicy,
): ProfileQualityDecisionReason? {
    val oldLoaded = requireNotNull(active.loadedLatencyMillis)
    val newLoaded = requireNotNull(reserve.loadedLatencyMillis)
    val oldGoodput = active.goodputBytesPerSecond
    val newGoodput = reserve.goodputBytesPerSecond
    val gain = policy.minimumGainPercent.toDouble() / PercentScale
    return when {
        newGoodput >= oldGoodput * (1 + gain) &&
            newLoaded <= oldLoaded + policy.maximumLoadedLatencyIncreaseMs -> {
            ProfileQualityDecisionReason.PromotedForGoodput
        }

        oldLoaded > 0 && newLoaded < oldLoaded && newLoaded <= oldLoaded * (1 - gain) &&
            newGoodput >= oldGoodput -> {
            ProfileQualityDecisionReason.PromotedForLoadedLatency
        }

        else -> {
            null
        }
    }
}

private const val PercentScale = 100
