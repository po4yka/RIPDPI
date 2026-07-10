package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionLifecycleTest {
    @Test
    fun `legacy serialized subscription defaults lifecycle metadata`() {
        val decoded =
            RipDpiJson.decodeFromString(
                Subscription.serializer(),
                """{"link":"https://example.test/sub"}""",
            )

        assertEquals(SubscriptionLifecycleState.UNKNOWN, decoded.lifecycleState)
        assertEquals(0L, decoded.lastRefreshAttemptAtEpochMillis)
        assertNull(decoded.lastRefreshFailure)
    }

    @Test
    fun `lifecycle and typed failure round trip`() {
        val expected =
            Subscription(
                lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
                lastRefreshAttemptAtEpochMillis = 42L,
                lastRefreshFailure = SubscriptionRefreshFailure.UNAVAILABLE,
            )

        val decoded =
            RipDpiJson.decodeFromString(
                Subscription.serializer(),
                RipDpiJson.encodeToString(Subscription.serializer(), expected),
            )

        assertEquals(expected, decoded)
    }

    @Test
    fun `cached unix-second expiry is immediately actionable`() {
        val subscription = Subscription(expiryDate = 1_700_000_000L)

        assertEquals(
            SubscriptionClientSignal.EXPIRED,
            subscription.clientSignalAt(nowEpochMillis = 1_700_000_000_000L),
        )
    }

    @Test
    fun `first failed attempt without a successful refresh is stale`() {
        val subscription =
            Subscription(
                lastRefreshAttemptAtEpochMillis = 1_000L,
                lastRefreshFailure = SubscriptionRefreshFailure.NETWORK_ERROR,
            )

        assertEquals(SubscriptionClientSignal.STALE, subscription.clientSignalAt(nowEpochMillis = 1_000L))
    }

    @Test
    fun `transient failure becomes stale at two effective refresh intervals`() {
        val subscription =
            Subscription(
                autoUpdateDelay = 60L,
                lastUpdated = 1_000L,
                lastRefreshAttemptAtEpochMillis = 2_000L,
                lastRefreshFailure = SubscriptionRefreshFailure.SERVER_ERROR,
            )
        val threshold = 1_000L + 2L * 60L * 60_000L

        assertNull(subscription.clientSignalAt(nowEpochMillis = threshold - 1L))
        assertEquals(SubscriptionClientSignal.STALE, subscription.clientSignalAt(nowEpochMillis = threshold))
    }

    @Test
    fun `terminal failures preserve deterministic severity order`() {
        val groups =
            listOf(
                group(
                    "stale",
                    Subscription(
                        lastRefreshAttemptAtEpochMillis = 1L,
                        lastRefreshFailure = SubscriptionRefreshFailure.NETWORK_ERROR,
                    ),
                ),
                group("unavailable", Subscription(lifecycleState = SubscriptionLifecycleState.UNAVAILABLE)),
                group(
                    "revoked",
                    Subscription(
                        lifecycleState = SubscriptionLifecycleState.SUSPENDED,
                        lastRefreshFailure = SubscriptionRefreshFailure.REVOKED,
                    ),
                ),
            )

        assertEquals(
            listOf(
                SubscriptionClientSignal.REVOKED,
                SubscriptionClientSignal.UNAVAILABLE,
                SubscriptionClientSignal.STALE,
            ),
            actionableSubscriptionSignals(groups, nowEpochMillis = 1L).map { it.second },
        )
    }

    private fun group(
        id: String,
        subscription: Subscription,
    ): ProxyGroup =
        ProxyGroup(
            id = id,
            name = id,
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = false,
            subscription = subscription,
        )
}
