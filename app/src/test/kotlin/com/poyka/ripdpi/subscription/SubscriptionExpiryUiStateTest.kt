package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionExpiryUiStateTest {
    @Test
    fun `warning begins exactly seven days before expiry`() {
        val now = 1_000L
        val state = subscriptionExpiryUiState(listOf(group("a", now + SevenDaysMillis)), now)

        assertEquals(SubscriptionExpiryStatus.EXPIRING, state.attention?.status)
        assertEquals(7L, state.attention?.daysRemaining)
    }

    @Test
    fun `subscription outside seven-day window is active`() {
        val now = 1_000L
        val state = subscriptionExpiryUiState(listOf(group("a", now + SevenDaysMillis + 1L)), now)

        assertNull(state.attention)
        assertEquals(SubscriptionExpiryStatus.ACTIVE, state.items.single().status)
    }

    @Test
    fun `expiry is terminal when now equals expiry`() {
        val state = subscriptionExpiryUiState(listOf(group("a", 1_000L)), nowMillis = 1_000L)

        assertEquals(SubscriptionExpiryStatus.EXPIRED, state.attention?.status)
        assertEquals(0L, state.attention?.daysRemaining)
    }

    @Test
    fun `expired outranks expiring and aggregate count includes both`() {
        val now = 1_000L
        val state =
            subscriptionExpiryUiState(
                listOf(group("soon", now + 1_000L), group("expired", now - 1L)),
                now,
            )

        assertEquals("expired", state.attention?.groupId)
        assertEquals(2, state.affectedCount)
    }

    @Test
    fun `terminal invalidation needs attention without pretending expiry is known`() {
        val state =
            subscriptionExpiryUiState(
                listOf(group("a", null, SubscriptionRefreshFailure.INVALIDATED)),
                nowMillis = 1_000L,
            )

        assertEquals(SubscriptionExpiryStatus.INVALIDATED, state.attention?.status)
        assertNull(state.attention?.daysRemaining)
    }

    private fun group(
        id: String,
        expiry: Long?,
        failure: SubscriptionRefreshFailure? = null,
    ) = ProxyGroup(
        id = id,
        name = id,
        type = ProxyGroupType.SUBSCRIPTION,
        order = 0,
        isSelector = false,
        subscription =
            Subscription(
                tokenExpiresAtEpochMillis = expiry,
                lastRefreshFailure = failure,
            ),
    )
}
