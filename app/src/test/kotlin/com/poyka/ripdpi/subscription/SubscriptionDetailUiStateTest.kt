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
 *  - bootstrap tokens are flagged distinct from persistent subscription tokens.
 */
class SubscriptionDetailUiStateTest {
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
}
