package com.poyka.ripdpi.services.dns.bootstrap

/**
 * Outcome of a [ScopedBootstrapResolver] resolution attempt.
 *
 * There is intentionally no ISP-DNS-fallback variant. The type system prevents
 * callers from accidentally routing to general system DNS on bootstrap failure.
 */
sealed class BootstrapResult {
    /**
     * Bootstrap resolution succeeded.
     *
     * @param addresses  Resolved addresses (pinned or system-resolved).
     * @param fromCache  True when the answer was served from the last-known-good cache.
     */
    data class Success(
        val addresses: List<String>,
        val fromCache: Boolean = false,
    ) : BootstrapResult()

    /**
     * The requested name or query type is not permitted during bootstrap.
     *
     * Callers must not retry via ISP DNS. The VPN bootstrap must treat this as
     * a hard denial for that name.
     */
    data object BootstrapDenied : BootstrapResult()

    /**
     * Bootstrap resolution was attempted but all paths failed.
     *
     * Callers must not fall through to ISP DNS. The VPN startup must surface
     * this as a typed failure state.
     *
     * @param reason Human-readable description of the failure (for diagnostics only).
     */
    data class BootstrapFailure(
        val reason: String,
    ) : BootstrapResult()
}
