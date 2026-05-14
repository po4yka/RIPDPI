package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-then-green TDD coverage for per-device subscription token UX:
 *  - the detail view-state redacts secrets (URL, token) unless explicitly revealed,
 *  - bootstrap tokens are flagged distinct from persistent subscription tokens,
 *  - a shared-link heuristic flags payloads that look multi-user / all-fleet,
 *  - refresh failures classify into expired / revoked / rate-limited / unreachable
 *    without carrying the URL.
 */
class SubscriptionTokenUxTest {
    @Test
    fun `detail state redacts the link and token by default`() {
        val subscription =
            Subscription(
                link = "https://provider.example.com/sub/deadbeefdeadbeef",
                token = "super-secret-token-fixture",
                kind = SubscriptionKind.LONG_LIVED,
            )

        val state = subscriptionDetailUiState(subscription, revealSecrets = false)

        assertFalse(state.linkRevealed)
        assertFalse(state.displayLink.contains("deadbeef"))
        assertFalse(state.displayToken.contains("super-secret-token-fixture"))
        assertTrue(state.displayLink.isNotBlank())
    }

    @Test
    fun `detail state exposes secrets only when reveal is requested`() {
        val subscription =
            Subscription(
                link = "https://provider.example.com/sub/hash",
                token = "tok",
            )

        val state = subscriptionDetailUiState(subscription, revealSecrets = true)

        assertTrue(state.linkRevealed)
        assertEquals("https://provider.example.com/sub/hash", state.displayLink)
        assertEquals("tok", state.displayToken)
    }

    @Test
    fun `detail state marks a bootstrap token distinct from a long-lived one`() {
        val bootstrap =
            subscriptionDetailUiState(Subscription(kind = SubscriptionKind.BOOTSTRAP), revealSecrets = false)
        val longLived =
            subscriptionDetailUiState(Subscription(kind = SubscriptionKind.LONG_LIVED), revealSecrets = false)

        assertTrue(bootstrap.isBootstrap)
        assertFalse(longLived.isBootstrap)
        // A bootstrap token has no refresh affordance.
        assertFalse(bootstrap.refreshable)
        assertTrue(longLived.refreshable)
    }

    @Test
    fun `a consumed bootstrap is not refreshable and reports its consumed timestamp`() {
        val state =
            subscriptionDetailUiState(
                Subscription(kind = SubscriptionKind.BOOTSTRAP, consumedAt = 1_715_000_000_000L),
                revealSecrets = false,
            )

        assertTrue(state.isBootstrap)
        assertFalse(state.refreshable)
        assertEquals(1_715_000_000_000L, state.consumedAtMillis)
    }

    @Test
    fun `shared-link heuristic flags a payload with multiple repeated uuids`() {
        val uuid = "11111111-1111-1111-1111-111111111111"
        val payload =
            listOf(
                "vless://$uuid@a.example.com:443#a",
                "vless://$uuid@b.example.com:443#b",
                "vless://$uuid@c.example.com:443#c",
            ).joinToString("\n")

        val warning = detectSharedLinkWarning(payload)

        assertTrue(warning is SharedLinkWarning.Detected)
    }

    @Test
    fun `shared-link heuristic passes a single-user payload`() {
        val payload =
            listOf(
                "vless://11111111-1111-1111-1111-111111111111@a.example.com:443#a",
                "vless://22222222-2222-2222-2222-222222222222@b.example.com:443#b",
            ).joinToString("\n")

        val warning = detectSharedLinkWarning(payload)

        assertEquals(SharedLinkWarning.None, warning)
    }

    @Test
    fun `refresh failure classification maps http codes without carrying the url`() {
        assertEquals(
            SubscriptionRefreshFailure.EXPIRED,
            classifyRefreshFailure(httpCode = 410, url = "https://secret.example.com/sub/x"),
        )
        assertEquals(SubscriptionRefreshFailure.REVOKED, classifyRefreshFailure(httpCode = 403, url = "u"))
        assertEquals(SubscriptionRefreshFailure.RATE_LIMITED, classifyRefreshFailure(httpCode = 429, url = "u"))
        assertEquals(SubscriptionRefreshFailure.UNREACHABLE, classifyRefreshFailure(httpCode = null, url = "u"))
        assertEquals(SubscriptionRefreshFailure.UNREACHABLE, classifyRefreshFailure(httpCode = 500, url = "u"))
    }

    @Test
    fun `refresh failure enum carries no url-bearing payload`() {
        // The classifier returns a bare enum; there is no field that could leak the URL.
        val failure = classifyRefreshFailure(httpCode = 410, url = "https://leak.example.com/sub/token")
        assertFalse(failure.name.contains("leak"))
        assertFalse(failure.name.contains("http"))
    }
}
