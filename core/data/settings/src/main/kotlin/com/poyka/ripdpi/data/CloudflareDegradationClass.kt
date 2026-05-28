package com.poyka.ripdpi.data

/**
 * Typed verdict for a Cloudflare-path degradation event.
 *
 * Use [userVisibleSummary] to obtain the four user-facing strings used by
 * settings and telemetry surfaces.
 */
enum class CloudflareDegradationClass {
    /** Cloudflare edge is rate-limiting or throttling the connection. */
    EdgeThrottling,

    /** The specific domain is blocked at the Cloudflare layer (hostname-level block). */
    DomainBlocked,

    /** The origin server behind Cloudflare is unreachable or returning errors. */
    OriginFailure,

    /** The client profile or protocol is incompatible with the Cloudflare path. */
    ClientProtocolFailure,

    /** Russian mobile-whitelist or blanket shutdown is preventing Cloudflare access. */
    WhitelistShutdown,
    ;

    /**
     * Returns one of the four user-visible summary strings.
     *
     * Mapping:
     * - EdgeThrottling, DomainBlocked → "degraded Cloudflare-like path"
     * - OriginFailure                → "origin issue"
     * - ClientProtocolFailure        → "profile issue"
     * - WhitelistShutdown            → "network restricted"
     */
    fun userVisibleSummary(): String =
        when (this) {
            EdgeThrottling, DomainBlocked -> "degraded Cloudflare-like path"
            OriginFailure -> "origin issue"
            ClientProtocolFailure -> "profile issue"
            WhitelistShutdown -> "network restricted"
        }
}
