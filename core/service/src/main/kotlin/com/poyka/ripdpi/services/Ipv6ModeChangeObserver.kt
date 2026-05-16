package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Ipv6Policy

/**
 * Tracks the last-applied [Ipv6Policy] and reports whether a VPN session
 * re-establish is required when the policy changes.
 *
 * Changing the IPv6 mode requires tearing down and re-building the VPN tunnel
 * because the TUN address, routes, and DNS configuration are baked in at
 * tunnel-establish time and cannot be updated in place.
 */
internal class Ipv6ModeChangeObserver {
    /** The most recently applied policy, or `null` if none has been applied yet. */
    var lastAppliedPolicy: Ipv6Policy? = null
        private set

    /**
     * Records that [policy] has been successfully applied to the active tunnel.
     * Subsequent calls to [requiresReestablish] will compare against this value.
     */
    fun policyApplied(policy: Ipv6Policy) {
        lastAppliedPolicy = policy
    }

    /**
     * Returns `true` when [newPolicy] differs from [lastAppliedPolicy] and a
     * VPN session re-establish is therefore required.
     *
     * Returns `false` when no policy has been applied yet (no existing tunnel
     * to tear down) or when the policy is unchanged.
     */
    fun requiresReestablish(newPolicy: Ipv6Policy): Boolean {
        val applied = lastAppliedPolicy ?: return false
        return applied != newPolicy
    }
}
