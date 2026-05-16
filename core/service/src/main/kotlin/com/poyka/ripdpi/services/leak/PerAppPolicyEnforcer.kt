package com.poyka.ripdpi.services.leak

/**
 * Determines whether a reconnect is required when the per-app allow/disallow
 * set changes between two tunnel configurations.
 *
 * A reconnect is required when the effective package set differs so that the
 * OS rebuilds the VPN interface with the new routing rules. This prevents
 * stale per-app routing from silently leaking traffic.
 */
internal class PerAppPolicyEnforcer {
    /**
     * Returns true when [prev] and [next] differ, meaning the OS must re-establish
     * the tunnel to apply the new per-app routing rules.
     */
    fun requiresReconnect(
        prev: Set<String>,
        next: Set<String>,
    ): Boolean = prev != next
}
