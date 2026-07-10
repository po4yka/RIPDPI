package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

/** Typed, persisted reason the latest subscription refresh did not become healthy. */
@Serializable
enum class SubscriptionRefreshFailure {
    EXPIRED,
    REVOKED,
    UNAVAILABLE,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    INVALID_PAYLOAD,
}

/** Actionable client-facing state derived from persisted subscription metadata. */
enum class SubscriptionClientSignal {
    EXPIRED,
    REVOKED,
    UNAVAILABLE,
    STALE,
}

/** WorkManager's minimum periodic cadence, duplicated here to keep the data model Android-free. */
private const val MinimumRefreshIntervalMinutes = 15L
private const val MillisPerMinute = 60_000L
private const val MillisPerSecond = 1_000L
private const val StaleIntervalMultiplier = 2L
private val TransientFailures =
    setOf(
        SubscriptionRefreshFailure.RATE_LIMITED,
        SubscriptionRefreshFailure.SERVER_ERROR,
        SubscriptionRefreshFailure.NETWORK_ERROR,
        SubscriptionRefreshFailure.INVALID_PAYLOAD,
    )

/** Returns a client signal at [nowEpochMillis], or `null` when no action is needed. */
fun Subscription.clientSignalAt(nowEpochMillis: Long): SubscriptionClientSignal? =
    terminalSignalAt(nowEpochMillis) ?: staleSignalAt(nowEpochMillis)

private fun Subscription.terminalSignalAt(nowEpochMillis: Long): SubscriptionClientSignal? =
    when {
        expiryDate > 0L && expiryDate <= nowEpochMillis / MillisPerSecond -> SubscriptionClientSignal.EXPIRED

        lifecycleState == SubscriptionLifecycleState.EXPIRED -> SubscriptionClientSignal.EXPIRED

        lifecycleState == SubscriptionLifecycleState.SUSPENDED &&
            lastRefreshFailure == SubscriptionRefreshFailure.REVOKED -> SubscriptionClientSignal.REVOKED

        lifecycleState == SubscriptionLifecycleState.UNAVAILABLE -> SubscriptionClientSignal.UNAVAILABLE

        lastRefreshFailure == SubscriptionRefreshFailure.EXPIRED -> SubscriptionClientSignal.EXPIRED

        lastRefreshFailure == SubscriptionRefreshFailure.REVOKED -> SubscriptionClientSignal.REVOKED

        lastRefreshFailure == SubscriptionRefreshFailure.UNAVAILABLE -> SubscriptionClientSignal.UNAVAILABLE

        else -> null
    }

private fun Subscription.staleSignalAt(nowEpochMillis: Long): SubscriptionClientSignal? {
    val effectiveIntervalMinutes = maxOf(autoUpdateDelay, MinimumRefreshIntervalMinutes)
    val staleAfterMillis = effectiveIntervalMinutes * MillisPerMinute * StaleIntervalMultiplier
    val transientFailure = lastRefreshFailure in TransientFailures
    return when {
        !transientFailure || lastRefreshAttemptAtEpochMillis <= 0L -> null
        lastUpdated <= 0L -> SubscriptionClientSignal.STALE
        nowEpochMillis - lastUpdated >= staleAfterMillis -> SubscriptionClientSignal.STALE
        else -> null
    }
}

/** Actionable subscription groups ordered by severity, then stable group id. */
fun actionableSubscriptionSignals(
    groups: List<ProxyGroup>,
    nowEpochMillis: Long,
): List<Pair<ProxyGroup, SubscriptionClientSignal>> =
    groups
        .mapNotNull { group -> group.subscription?.clientSignalAt(nowEpochMillis)?.let { group to it } }
        .sortedWith(compareBy<Pair<ProxyGroup, SubscriptionClientSignal>> { it.second.priority }.thenBy { it.first.id })

private val SubscriptionClientSignal.priority: Int
    get() =
        when (this) {
            SubscriptionClientSignal.EXPIRED,
            SubscriptionClientSignal.REVOKED,
            -> 0

            SubscriptionClientSignal.UNAVAILABLE -> 1

            SubscriptionClientSignal.STALE -> 2
        }
