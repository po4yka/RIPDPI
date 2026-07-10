package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-then-green TDD coverage for the subscription auto-update WorkManager
 * worker's pure logic: which subscriptions are eligible for an auto-update
 * pass, and the periodic interval clamp.
 *
 * Eligibility rules:
 *  - only [ProxyGroupType.SUBSCRIPTION] groups,
 *  - only when [Subscription.autoUpdate] is true,
 *  - never [SubscriptionKind.BOOTSTRAP] (single-use; polling a spent bootstrap
 *    URL yields a spurious 410 "subscription dead" alert).
 *
 * Interval clamp: the periodic request runs at the shortest configured
 * `autoUpdateDelay` across all eligible groups, never below the WorkManager
 * 15-minute floor.
 */
class SubscriptionAutoUpdateWorkerTest {
    private fun subscriptionGroup(
        id: String,
        autoUpdate: Boolean = true,
        kind: SubscriptionKind = SubscriptionKind.LONG_LIVED,
        autoUpdateDelayMinutes: Long = 60L,
    ) = ProxyGroup(
        id = id,
        name = "Group $id",
        type = ProxyGroupType.SUBSCRIPTION,
        order = 0,
        isSelector = false,
        subscription =
            Subscription(
                link = "https://example.com/$id",
                autoUpdate = autoUpdate,
                autoUpdateDelay = autoUpdateDelayMinutes,
                kind = kind,
            ),
    )

    private fun basicGroup(id: String) =
        ProxyGroup(
            id = id,
            name = "Basic $id",
            type = ProxyGroupType.BASIC,
            order = 0,
            isSelector = false,
        )

    @Test
    fun `enumeration includes only auto-updating long-lived subscription groups`() {
        val groups =
            listOf(
                subscriptionGroup("ll-on", autoUpdate = true, kind = SubscriptionKind.LONG_LIVED),
                subscriptionGroup("ll-off", autoUpdate = false, kind = SubscriptionKind.LONG_LIVED),
                basicGroup("basic"),
            )

        val due = subscriptionsDueForAutoUpdate(groups)

        assertEquals(listOf("ll-on"), due.map(ProxyGroup::id))
    }

    @Test
    fun `enumeration excludes bootstrap subscriptions even when auto-update is on`() {
        val groups =
            listOf(
                subscriptionGroup("ll", autoUpdate = true, kind = SubscriptionKind.LONG_LIVED),
                subscriptionGroup("boot", autoUpdate = true, kind = SubscriptionKind.BOOTSTRAP),
            )

        val due = subscriptionsDueForAutoUpdate(groups)

        assertEquals(listOf("ll"), due.map(ProxyGroup::id))
        assertTrue(due.none { it.subscription?.kind == SubscriptionKind.BOOTSTRAP })
    }

    @Test
    fun `interval clamp picks the shortest configured delay`() {
        val groups =
            listOf(
                subscriptionGroup("a", autoUpdateDelayMinutes = 360L),
                subscriptionGroup("b", autoUpdateDelayMinutes = 45L),
                subscriptionGroup("c", autoUpdateDelayMinutes = 120L),
            )

        assertEquals(45L, autoUpdateIntervalMinutes(groups))
    }

    @Test
    fun `interval clamp never goes below the WorkManager 15 minute floor`() {
        val groups = listOf(subscriptionGroup("fast", autoUpdateDelayMinutes = 5L))

        assertEquals(15L, autoUpdateIntervalMinutes(groups))
    }

    @Test
    fun `interval clamp ignores bootstrap delays`() {
        val groups =
            listOf(
                subscriptionGroup("boot", kind = SubscriptionKind.BOOTSTRAP, autoUpdateDelayMinutes = 1L),
                subscriptionGroup("ll", kind = SubscriptionKind.LONG_LIVED, autoUpdateDelayMinutes = 90L),
            )

        assertEquals(90L, autoUpdateIntervalMinutes(groups))
    }

    @Test
    fun `interval clamp falls back to the floor when no eligible groups exist`() {
        assertEquals(15L, autoUpdateIntervalMinutes(emptyList()))
    }

    @Test
    fun `enumeration excludes locally expired subscriptions`() {
        val group =
            subscriptionGroup("expired").copy(
                subscription =
                    subscriptionGroup("expired").subscription?.copy(tokenExpiresAtEpochMillis = 1_000L),
            )

        assertTrue(subscriptionsDueForAutoUpdate(listOf(group), nowMillis = 1_000L).isEmpty())
    }

    @Test
    fun `enumeration excludes terminally invalidated subscriptions`() {
        val group =
            subscriptionGroup("invalidated").copy(
                subscription =
                    subscriptionGroup("invalidated").subscription?.copy(
                        lastRefreshFailure = SubscriptionRefreshFailure.INVALIDATED,
                    ),
            )

        assertTrue(subscriptionsDueForAutoUpdate(listOf(group), nowMillis = 1_000L).isEmpty())
    }
}
