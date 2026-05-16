package com.poyka.ripdpi.services.dns.bootstrap

/**
 * Registry of pre-configured pinned IP addresses for bootstrap hostnames.
 *
 * When a pinned IP is present for a hostname the system resolver must not be
 * consulted — the pinned address is returned directly, eliminating any cold-start
 * DNS dependency for that endpoint.
 *
 * @param pinned Map from hostname to a pinned IPv4 or IPv6 address string.
 */
class PinnedEndpointRegistry(
    private val pinned: Map<String, String>,
) {
    /**
     * Return the pinned IP address for [hostname], or null when no pin is configured.
     *
     * A non-null result means the caller must use this address and must not
     * invoke the system resolver.
     */
    fun pinnedIpFor(hostname: String): String? = pinned[hostname]
}
