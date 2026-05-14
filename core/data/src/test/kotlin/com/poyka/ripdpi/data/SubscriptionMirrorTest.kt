package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.MirrorFetchOutcome
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorState
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the multi-delivery subscription mirror model: a per-device
 * subscription can carry multiple ordered delivery URLs (mirrors) with
 * failover, Cloudflare mirrors treated as optional rather than authoritative,
 * and full token / URL redaction in any diagnostic surface.
 */
class SubscriptionMirrorTest {
    private fun directMirror(
        id: String = "direct-1",
        token: String = "tkn-direct-fixture",
    ) = SubscriptionMirror(
        id = id,
        url = "https://direct.example.com/sub/$id",
        token = token,
        transport = SubscriptionMirrorTransport.DIRECT,
    )

    private fun cloudflareMirror(
        id: String = "cf-1",
        token: String = "tkn-cf-fixture",
    ) = SubscriptionMirror(
        id = id,
        url = "https://cf.example.com/sub/$id",
        token = token,
        transport = SubscriptionMirrorTransport.CLOUDFLARE,
    )

    @Test
    fun `mirror set stores multiple scoped delivery mirrors for one device`() {
        val set =
            SubscriptionMirrorSet(
                mirrors = listOf(directMirror("d1"), cloudflareMirror("c1"), directMirror("d2")),
            )

        assertEquals(3, set.mirrors.size)
        assertEquals(listOf("d1", "c1", "d2"), set.mirrors.map { it.id })
    }

    @Test
    fun `refresh order prefers non-Cloudflare direct delivery first`() {
        val set =
            SubscriptionMirrorSet(
                // Declared Cloudflare-first on purpose; refresh order must still
                // float the direct mirrors ahead of the Cloudflare ones.
                mirrors =
                    listOf(
                        cloudflareMirror("c1"),
                        directMirror("d1"),
                        cloudflareMirror("c2"),
                        directMirror("d2"),
                    ),
            )

        val order = set.refreshOrder().map { it.id }

        assertEquals(listOf("d1", "d2", "c1", "c2"), order)
    }

    @Test
    fun `a Cloudflare mirror failure does not block trying non-Cloudflare mirrors`() {
        val set =
            SubscriptionMirrorSet(
                mirrors = listOf(cloudflareMirror("c1"), directMirror("d1")),
            )

        // c1 fails, d1 succeeds — the run must reach d1 and report it as the winner.
        val result =
            set.runRefresh { mirror ->
                if (mirror.transport == SubscriptionMirrorTransport.CLOUDFLARE) {
                    MirrorFetchOutcome.Failure("connection reset")
                } else {
                    MirrorFetchOutcome.Success("payload-from-${mirror.id}")
                }
            }

        assertTrue(result.succeeded)
        assertEquals("d1", result.winningMirrorId)
        assertEquals("payload-from-d1", result.payload)
        // The Cloudflare mirror is recorded as attempted-and-degraded.
        assertEquals(SubscriptionMirrorState.DEGRADED, result.mirrorStates.getValue("c1"))
        assertEquals(SubscriptionMirrorState.HEALTHY, result.mirrorStates.getValue("d1"))
    }

    @Test
    fun `all mirrors failing yields a failed refresh with every mirror degraded`() {
        val set =
            SubscriptionMirrorSet(
                mirrors = listOf(directMirror("d1"), cloudflareMirror("c1")),
            )

        val result = set.runRefresh { MirrorFetchOutcome.Failure("unreachable") }

        assertFalse(result.succeeded)
        assertNull(result.winningMirrorId)
        assertNull(result.payload)
        assertEquals(SubscriptionMirrorState.DEGRADED, result.mirrorStates.getValue("d1"))
        assertEquals(SubscriptionMirrorState.DEGRADED, result.mirrorStates.getValue("c1"))
    }

    @Test
    fun `the first successful mirror short-circuits the remaining attempts`() {
        val attempted = mutableListOf<String>()
        val set =
            SubscriptionMirrorSet(
                mirrors = listOf(directMirror("d1"), directMirror("d2"), cloudflareMirror("c1")),
            )

        val result =
            set.runRefresh { mirror ->
                attempted += mirror.id
                MirrorFetchOutcome.Success("ok")
            }

        assertEquals("d1", result.winningMirrorId)
        // d2 and c1 are never attempted once d1 succeeds.
        assertEquals(listOf("d1"), attempted)
    }

    @Test
    fun `diagnostic rendering redacts every mirror token and full URL`() {
        val set =
            SubscriptionMirrorSet(
                mirrors =
                    listOf(
                        directMirror("d1", token = "super-secret-direct-token-fixture"),
                        cloudflareMirror("c1", token = "super-secret-cf-token-fixture"),
                    ),
            )

        val redacted = set.toRedactedDiagnostics()

        // No raw token text anywhere.
        assertFalse(redacted.contains("super-secret-direct-token-fixture"))
        assertFalse(redacted.contains("super-secret-cf-token-fixture"))
        // No full delivery URL path anywhere — only the host is allowed through.
        assertFalse(redacted.contains("/sub/d1"))
        assertFalse(redacted.contains("/sub/c1"))
        // The host is still surfaced so the user can tell mirrors apart.
        assertTrue(redacted.contains("direct.example.com"))
        assertTrue(redacted.contains("cf.example.com"))
    }

    @Test
    fun `a single mirror toRedacted of one mirror never leaks the token`() {
        val mirror = directMirror("d1", token = "leak-me-if-you-can-fixture")

        val line = mirror.toRedactedLine()

        assertFalse(line.contains("leak-me-if-you-can-fixture"))
        assertTrue(line.contains("direct.example.com"))
    }

    @Test
    fun `last-succeeded mirror is surfaced for the UI without exposing secrets`() {
        val set =
            SubscriptionMirrorSet(
                mirrors = listOf(cloudflareMirror("c1"), directMirror("d1", token = "ui-token-fixture")),
            )

        val result =
            set.runRefresh { mirror ->
                if (mirror.transport == SubscriptionMirrorTransport.CLOUDFLARE) {
                    MirrorFetchOutcome.Failure("503")
                } else {
                    MirrorFetchOutcome.Success("payload")
                }
            }

        val summary = result.toUiSummary()
        assertTrue(summary.lastRefreshMirrorLabel.contains("direct.example.com"))
        assertFalse(summary.lastRefreshMirrorLabel.contains("ui-token-fixture"))
        // The degraded Cloudflare mirror is reported as degraded, by host only.
        assertTrue(summary.degradedMirrorLabels.any { it.contains("cf.example.com") })
        assertTrue(summary.degradedMirrorLabels.none { it.contains("tkn-cf-fixture") })
    }

    @Test
    fun `an empty mirror set is a no-op refresh, not a crash`() {
        val set = SubscriptionMirrorSet(mirrors = emptyList())

        val result = set.runRefresh { MirrorFetchOutcome.Success("never reached") }

        assertFalse(result.succeeded)
        assertNull(result.winningMirrorId)
        assertTrue(result.mirrorStates.isEmpty())
    }
}
