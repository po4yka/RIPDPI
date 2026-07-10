package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.math.ceil

internal const val SevenDaysMillis: Long = 7L * 24L * 60L * 60L * 1_000L
private const val OneDayMillis: Long = 24L * 60L * 60L * 1_000L
private const val ExpiryUiRefreshIntervalMillis: Long = 60_000L

fun interface SubscriptionExpiryClock {
    fun nowMillis(): Long

    fun ticks(): Flow<Long> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(nowMillis())
                delay(ExpiryUiRefreshIntervalMillis)
            }
        }
}

enum class SubscriptionExpiryStatus {
    ACTIVE,
    EXPIRING,
    EXPIRED,
    INVALIDATED,
    UNKNOWN,
}

data class SubscriptionExpiryItemUiState(
    val groupId: String,
    val groupName: String,
    val memberCount: Int,
    val status: SubscriptionExpiryStatus,
    val daysRemaining: Long?,
    val details: SubscriptionDetailUiState,
    val lastRefreshFailure: SubscriptionRefreshFailure?,
)

data class SubscriptionExpirySummaryUiState(
    val items: List<SubscriptionExpiryItemUiState> = emptyList(),
    val attention: SubscriptionExpiryItemUiState? = null,
    val affectedCount: Int = 0,
)

fun subscriptionExpiryUiState(
    groups: List<ProxyGroup>,
    nowMillis: Long,
): SubscriptionExpirySummaryUiState {
    val items =
        groups
            .filter { it.type == ProxyGroupType.SUBSCRIPTION && it.subscription != null }
            .map { group -> group.toExpiryItem(nowMillis) }
    val affected = items.filter { it.status.needsAttention }
    return SubscriptionExpirySummaryUiState(
        items = items,
        attention =
            affected.minWithOrNull(
                compareBy({ it.status.priority }, { it.details.tokenExpiryMillis ?: Long.MAX_VALUE }),
            ),
        affectedCount = affected.size,
    )
}

private fun ProxyGroup.toExpiryItem(nowMillis: Long): SubscriptionExpiryItemUiState {
    val subscription = requireNotNull(subscription)
    val expiry = subscription.tokenExpiresAtEpochMillis
    val status =
        when {
            expiry != null && nowMillis >= expiry -> SubscriptionExpiryStatus.EXPIRED
            subscription.lastRefreshFailure?.isTerminal == true -> SubscriptionExpiryStatus.INVALIDATED
            expiry == null -> SubscriptionExpiryStatus.UNKNOWN
            expiry - nowMillis <= SevenDaysMillis -> SubscriptionExpiryStatus.EXPIRING
            else -> SubscriptionExpiryStatus.ACTIVE
        }
    val daysRemaining =
        expiry?.let { value ->
            if (value <= nowMillis) 0L else ceil((value - nowMillis).toDouble() / OneDayMillis).toLong()
        }
    return SubscriptionExpiryItemUiState(
        groupId = id,
        groupName = name,
        memberCount = members.size,
        status = status,
        daysRemaining = daysRemaining,
        details = subscriptionDetailUiState(subscription, revealSecrets = false),
        lastRefreshFailure = subscription.lastRefreshFailure,
    )
}

private val SubscriptionExpiryStatus.needsAttention: Boolean
    get() =
        this == SubscriptionExpiryStatus.EXPIRING ||
            this == SubscriptionExpiryStatus.EXPIRED ||
            this == SubscriptionExpiryStatus.INVALIDATED

private val SubscriptionExpiryStatus.priority: Int
    get() =
        when (this) {
            SubscriptionExpiryStatus.EXPIRED -> 0
            SubscriptionExpiryStatus.INVALIDATED -> 1
            SubscriptionExpiryStatus.EXPIRING -> 2
            SubscriptionExpiryStatus.ACTIVE -> 3
            SubscriptionExpiryStatus.UNKNOWN -> 4
        }
