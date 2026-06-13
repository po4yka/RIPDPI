package com.poyka.ripdpi.ui.components.indicators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RipDpiStaleDataBadgeTest {
    private val pollInterval = 1.seconds

    @Test
    fun `no badge while the snapshot is younger than twice the poll interval`() {
        // A healthy 1 s poll keeps age well under the 2 s gate — no badge ever flashes.
        assertNull(liveStaleBadgeTier(0.seconds, pollInterval))
        assertNull(liveStaleBadgeTier(900.milliseconds, pollInterval))
        assertNull(liveStaleBadgeTier(1.seconds, pollInterval))
        assertNull(liveStaleBadgeTier(1_999.milliseconds, pollInterval))
    }

    @Test
    fun `badge appears exactly at the two-times-poll-interval threshold`() {
        // 2 s at a 1 s poll: the boundary is inclusive and deterministic (no flicker).
        assertEquals(RipDpiStaleTier.Recent, liveStaleBadgeTier(2.seconds, pollInterval))
    }

    @Test
    fun `threshold decision is stable for a fixed age - cannot flicker`() {
        // Pure + monotonic: the same age always yields the same verdict across calls,
        // so repeated recompositions at a fixed age never toggle the badge on and off.
        val justBelow = 1_999.milliseconds
        val atThreshold = 2.seconds
        repeat(50) {
            assertNull(liveStaleBadgeTier(justBelow, pollInterval))
            assertEquals(RipDpiStaleTier.Recent, liveStaleBadgeTier(atThreshold, pollInterval))
        }
    }

    @Test
    fun `the fresh tier is never shown by the badge - it collapses to recent`() {
        // staleTierFor would call 2-5 s "Fresh" (a green updating pulse); the live
        // badge only renders for non-fresh data, so that window must read as Recent.
        assertEquals(RipDpiStaleTier.Fresh, staleTierFor(3.seconds))
        assertEquals(RipDpiStaleTier.Recent, liveStaleBadgeTier(3.seconds, pollInterval))
    }

    @Test
    fun `older snapshots escalate through the aging and stale tiers`() {
        assertEquals(RipDpiStaleTier.Recent, liveStaleBadgeTier(30.seconds, pollInterval))
        assertEquals(RipDpiStaleTier.Aging, liveStaleBadgeTier(3.minutes, pollInterval))
        assertEquals(RipDpiStaleTier.Stale, liveStaleBadgeTier(10.minutes, pollInterval))
        assertEquals(RipDpiStaleTier.Expired, liveStaleBadgeTier(45.minutes, pollInterval))
    }

    @Test
    fun `a slower background poll widens the fresh window before the badge shows`() {
        // At a 5 s background cadence the gate is 10 s, so a 6 s-old snapshot that would
        // be stale on a 1 s poll is still within tolerance.
        val backgroundPoll = 5.seconds
        assertNull(liveStaleBadgeTier(6.seconds, backgroundPoll))
        assertEquals(RipDpiStaleTier.Recent, liveStaleBadgeTier(10.seconds, backgroundPoll))
    }
}
