package com.poyka.ripdpi.services.dns.bootstrap

/**
 * Bootstrap DNS resolver scoped to an allowlist with pinned-IP preference.
 *
 * Resolution order:
 *  1. Reject [name] if not in [allowlist] → [BootstrapResult.BootstrapDenied].
 *  2. Reject AAAA [qtype] if not permitted by [ipv6Policy] → [BootstrapResult.BootstrapDenied].
 *  3. Return pinned IP from [pinnedRegistry] when present (system resolver not called).
 *  4. Check [cache] for a valid last-known-good entry.
 *  5. Call [systemResolver]; store result in [cache] on success.
 *  6. On system-resolver failure → [BootstrapResult.BootstrapFailure].
 *
 * There is no ISP-DNS-fallback branch. [BootstrapResult] has no such variant.
 *
 * @param allowlist        Set of hostnames permitted during bootstrap.
 * @param pinnedRegistry   Pinned IP addresses that bypass system resolution.
 * @param ipv6Policy       Whether AAAA queries are permitted.
 * @param cache            Last-known-good cache for bootstrap-derived answers.
 * @param systemResolver   Fake-injectable system DNS: (name, qtype) → addresses or null.
 */
class ScopedBootstrapResolver(
    private val allowlist: BootstrapAllowlist,
    private val pinnedRegistry: PinnedEndpointRegistry,
    private val ipv6Policy: Ipv6BootstrapPolicy,
    private val cache: LastKnownGoodCache,
    private val systemResolver: (String, String) -> List<String>?,
) {
    /**
     * Resolve [name] for [qtype], returning a [BootstrapResult].
     *
     * The return type is sealed; there is no variant for ISP DNS fallback.
     */
    fun resolve(
        name: String,
        qtype: String,
    ): BootstrapResult = checkPolicy(name, qtype) ?: resolveAddress(name, qtype)

    /** Returns a denial result when policy blocks this query, null when the query is permitted. */
    private fun checkPolicy(
        name: String,
        qtype: String,
    ): BootstrapResult? {
        val nameAllowed = allowlist.check(name) is AllowlistResult.Permitted
        val qtypeAllowed = qtype != "AAAA" || ipv6Policy.isAaaaPermitted()
        return if (nameAllowed && qtypeAllowed) null else BootstrapResult.BootstrapDenied
    }

    /** Resolves the address from pinned registry, cache, or system resolver. */
    private fun resolveAddress(
        name: String,
        qtype: String,
    ): BootstrapResult {
        val pinned = pinnedRegistry.pinnedIpFor(name)
        val cached = if (pinned == null) cache.lookup(name, qtype) else null
        return when {
            pinned != null -> BootstrapResult.Success(addresses = listOf(pinned))
            cached != null -> BootstrapResult.Success(addresses = cached.addresses, fromCache = true)
            else -> resolveFromSystem(name, qtype)
        }
    }

    /** Calls the system resolver and caches the result, or returns [BootstrapResult.BootstrapFailure]. */
    private fun resolveFromSystem(
        name: String,
        qtype: String,
    ): BootstrapResult {
        val resolved = systemResolver(name, qtype)
        return if (resolved != null) {
            cache.store(name, qtype, resolved)
            BootstrapResult.Success(addresses = resolved)
        } else {
            BootstrapResult.BootstrapFailure(reason = "system resolver returned null for $name/$qtype")
        }
    }
}
