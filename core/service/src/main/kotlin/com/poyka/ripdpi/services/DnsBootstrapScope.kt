package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.SplitStrictDnsPolicy

/**
 * Wraps bootstrap resolution so that it is scoped to pinned IP endpoints and
 * cannot re-enter the TUN tunnel.
 *
 * The bootstrap plane uses a pinned IP (set in
 * [com.poyka.ripdpi.data.SplitStrictResolverSpec.pinnedBootstrapIp]) to resolve
 * the upstream encrypted-resolver hostname before the tunnel is fully active.
 * Routing that query back through the TUN would create a loop, so this class
 * enforces that only the pinned IP (or explicitly allowed addresses) may be
 * used for bootstrap lookups.
 *
 * @param policy             The split-strict policy carrying bootstrap plane config.
 * @param tunAddressPrefix   Network prefix of addresses reachable only via TUN.
 *                           Queries targeting these are rejected as would-be loops.
 */
internal class DnsBootstrapScope(
    private val policy: SplitStrictDnsPolicy,
    private val tunAddressPrefix: String = "198.18.",
) {
    /**
     * Returns the pinned bootstrap IP from the policy, or null when not configured.
     *
     * Callers must use this address (not a hostname) for the initial resolver
     * lookup. Passing the result into a DNS query that routes through the TUN is
     * a TUN-loop and will be rejected by [assertNotTunAddress].
     */
    fun pinnedBootstrapIp(): String? = policy.bootstrap.pinnedBootstrapIp.takeIf { it.isNotBlank() }

    /**
     * Asserts that [address] is not a TUN-internal address, preventing re-entry
     * into the tunnel during bootstrap resolution.
     *
     * @throws IllegalStateException if [address] would route back into the TUN.
     */
    fun assertNotTunAddress(address: String) {
        check(!address.startsWith(tunAddressPrefix)) {
            "Bootstrap resolution must not route through TUN address $address — " +
                "this would create a DNS loop inside the tunnel."
        }
    }

    /**
     * Returns true when [address] is safe to use for bootstrap resolution,
     * i.e. it does not route back into the TUN.
     */
    fun isSafeBootstrapAddress(address: String): Boolean = !address.startsWith(tunAddressPrefix)
}
