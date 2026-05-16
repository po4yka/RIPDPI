package com.poyka.ripdpi.services.dns.failover

/**
 * Identifies which encrypted protocol a [ResolverEndpoint] uses.
 *
 * Only encrypted variants are present. There is intentionally no plaintext
 * or system-DNS variant — the type system prevents callers from accidentally
 * introducing a plaintext fallback for proxy-class domains.
 */
enum class EncryptedResolverKind {
    DohPost,
    Dot,
    Doq,
}

/**
 * Identifies an outbound through which a resolver attempt is routed.
 *
 * An empty string denotes the active (primary) outbound.
 */
typealias OutboundId = String

/**
 * A single encrypted resolver endpoint with its routing constraints.
 *
 * @param kind             Protocol used to reach this resolver.
 * @param host             DoH URL for [EncryptedResolverKind.DohPost]; hostname for DoT / DoQ.
 * @param allowedOutbounds Outbound IDs through which this endpoint may be reached.
 *                         An empty list means the active outbound is the only candidate.
 */
data class ResolverEndpoint(
    val kind: EncryptedResolverKind,
    val host: String,
    val allowedOutbounds: List<OutboundId> = emptyList(),
)

/**
 * Policy governing strict encrypted DNS failover for proxy-class domains.
 *
 * Failover order:
 *  1. Try [primary] via the active outbound.
 *  2. On failure, try each [secondaries] endpoint via the active outbound.
 *  3. On exhaustion, try each endpoint via [fallbackOutbounds] (encrypted only).
 *  4. If everything fails, the resolver returns [StrictDnsResult.StrictFailure].
 *
 * There is no plaintext system-DNS option in this type. Callers cannot construct
 * a policy that permits plaintext fallback for proxy-class domains.
 *
 * @param primary          First resolver to attempt.
 * @param secondaries      Additional resolvers tried via the active outbound on primary failure.
 * @param fallbackOutbounds Outbound IDs tried as last resort before strict failure.
 */
data class FailoverPolicy(
    val primary: ResolverEndpoint,
    val secondaries: List<ResolverEndpoint> = emptyList(),
    val fallbackOutbounds: List<OutboundId> = emptyList(),
)

/** Represents the answer returned by a strict encrypted DNS resolver attempt. */
sealed class StrictDnsResult {
    /**
     * Resolution succeeded.
     *
     * @param addresses Resolved IP addresses.
     * @param outboundUsed Outbound that delivered the successful response.
     * @param fromCache    True when the answer was served from a valid (non-expired) cache entry.
     */
    data class Success(
        val addresses: List<String>,
        val outboundUsed: OutboundId,
        val fromCache: Boolean = false,
    ) : StrictDnsResult()

    /**
     * All encrypted resolver paths were exhausted without a successful response.
     *
     * Callers MUST translate this to SERVFAIL / blocked and MUST NOT fall back
     * to plaintext system DNS for proxy-class domains.
     */
    data object StrictFailure : StrictDnsResult()
}
