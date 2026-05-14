package com.poyka.ripdpi.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-then-green TDD coverage for the [Subscription] entity gaining a
 * [SubscriptionKind] discriminator and a `consumedAt` timestamp, per the
 * bootstrap one-time-token import flow. A long-lived subscription is
 * refetchable; a bootstrap subscription is single-use and, once consumed,
 * carries a `consumedAt` stamp and is never re-fetched.
 */
class SubscriptionKindTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `subscription kind defaults to long-lived and consumedAt defaults to null`() {
        val subscription = Subscription(link = "https://example.com/sub")

        assertEquals(SubscriptionKind.LONG_LIVED, subscription.kind)
        assertNull(subscription.consumedAt)
    }

    @Test
    fun `a long-lived subscription round-trips through json`() {
        val group =
            ProxyGroup(
                id = "sub-ll",
                name = "Fleet",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = false,
                subscription =
                    Subscription(
                        link = "https://example.com/sub/hash",
                        kind = SubscriptionKind.LONG_LIVED,
                        consumedAt = null,
                    ),
            )

        val decoded =
            json.decodeFromString(
                ProxyGroup.serializer(),
                json.encodeToString(ProxyGroup.serializer(), group),
            )

        assertEquals(group, decoded)
        assertEquals(SubscriptionKind.LONG_LIVED, decoded.subscription?.kind)
        assertNull(decoded.subscription?.consumedAt)
    }

    @Test
    fun `a consumed bootstrap subscription round-trips through json`() {
        val group =
            ProxyGroup(
                id = "sub-boot",
                name = "First boot",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 1,
                isSelector = false,
                subscription =
                    Subscription(
                        // The raw URL is discarded post-consume; only the hash is kept.
                        link = "",
                        token = "",
                        kind = SubscriptionKind.BOOTSTRAP,
                        consumedAt = 1_715_000_000_000L,
                    ),
            )

        val decoded =
            json.decodeFromString(
                ProxyGroup.serializer(),
                json.encodeToString(ProxyGroup.serializer(), group),
            )

        assertEquals(group, decoded)
        assertEquals(SubscriptionKind.BOOTSTRAP, decoded.subscription?.kind)
        assertEquals(1_715_000_000_000L, decoded.subscription?.consumedAt)
    }

    @Test
    fun `legacy payload without a kind field decodes as long-lived`() {
        // A subscription persisted before the kind field existed.
        val legacy = """{"link":"https://example.com/sub","token":"t"}"""

        val decoded = json.decodeFromString(Subscription.serializer(), legacy)

        assertEquals(SubscriptionKind.LONG_LIVED, decoded.kind)
        assertNull(decoded.consumedAt)
    }

    @Test
    fun `a bootstrap subscription reports consumed state`() {
        val unconsumed = Subscription(kind = SubscriptionKind.BOOTSTRAP, consumedAt = null)
        val consumed = Subscription(kind = SubscriptionKind.BOOTSTRAP, consumedAt = 1L)

        assertTrue(!unconsumed.isConsumed)
        assertTrue(consumed.isConsumed)
    }
}
