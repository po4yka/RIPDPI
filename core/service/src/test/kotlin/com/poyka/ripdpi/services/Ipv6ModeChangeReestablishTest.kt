package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Ipv6Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [Ipv6ModeChangeObserver] detects IPv6 policy changes and signals re-establish.
 *
 * Acceptance criterion: changing the IPv6 mode forces a VPN session re-establish.
 * The observer tracks the last applied policy and reports whether a tunnel restart
 * is required when the active policy differs.
 */
class Ipv6ModeChangeReestablishTest {
    @Test
    fun noReestablishNeededWhenPolicyUnchanged() {
        val observer = Ipv6ModeChangeObserver()
        observer.policyApplied(Ipv6Policy.IPV4_ONLY)

        val needsReestablish = observer.requiresReestablish(Ipv6Policy.IPV4_ONLY)
        assertFalse(
            "Same policy must not trigger re-establish",
            needsReestablish,
        )
    }

    @Test
    fun reestablishNeededWhenPolicyChangesFromIpv4OnlyToAllow() {
        val observer = Ipv6ModeChangeObserver()
        observer.policyApplied(Ipv6Policy.IPV4_ONLY)

        val needsReestablish = observer.requiresReestablish(Ipv6Policy.ALLOW)
        assertTrue(
            "IPV4_ONLY → ALLOW must trigger re-establish",
            needsReestablish,
        )
    }

    @Test
    fun reestablishNeededWhenPolicyChangesFromAllowToIpv4Only() {
        val observer = Ipv6ModeChangeObserver()
        observer.policyApplied(Ipv6Policy.ALLOW)

        val needsReestablish = observer.requiresReestablish(Ipv6Policy.IPV4_ONLY)
        assertTrue(
            "ALLOW → IPV4_ONLY must trigger re-establish",
            needsReestablish,
        )
    }

    @Test
    fun reestablishNeededWhenPolicyChangesFromBlockToAllow() {
        val observer = Ipv6ModeChangeObserver()
        observer.policyApplied(Ipv6Policy.BLOCK)

        val needsReestablish = observer.requiresReestablish(Ipv6Policy.ALLOW)
        assertTrue(
            "BLOCK → ALLOW must trigger re-establish",
            needsReestablish,
        )
    }

    @Test
    fun noReestablishWhenNoPolicyEverApplied() {
        // Before any policy is applied the observer has no baseline — no diff, no restart.
        val observer = Ipv6ModeChangeObserver()
        assertFalse(
            "Observer with no applied policy must not require re-establish",
            observer.requiresReestablish(Ipv6Policy.IPV4_ONLY),
        )
    }

    @Test
    fun lastAppliedPolicyTracksUpdates() {
        val observer = Ipv6ModeChangeObserver()
        observer.policyApplied(Ipv6Policy.IPV4_ONLY)
        assertEquals(Ipv6Policy.IPV4_ONLY, observer.lastAppliedPolicy)

        observer.policyApplied(Ipv6Policy.ALLOW)
        assertEquals(Ipv6Policy.ALLOW, observer.lastAppliedPolicy)
    }
}
