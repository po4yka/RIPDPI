package com.poyka.ripdpi.services.dns.failover

/**
 * Abstraction over a single DNS query attempt via a specific outbound.
 *
 * Implementations are injected by tests via fakes; production code supplies
 * an implementation backed by the active encrypted resolver transport.
 */
fun interface EncryptedResolverTransport {
    /**
     * Attempt to resolve [domain] for [qtype] via [outbound] using [endpoint].
     *
     * @return Resolved addresses on success, or null on any transport / protocol error.
     */
    fun resolve(
        domain: String,
        qtype: String,
        endpoint: ResolverEndpoint,
        outbound: OutboundId,
    ): List<String>?
}

/**
 * Abstraction over a DNS cache for proxy-class domains.
 *
 * The cache is consulted before resolver attempts, enabling cache-assisted
 * recovery when all live resolver paths are momentarily unavailable.
 */
fun interface DnsCache {
    /**
     * Return cached addresses for [domain] / [qtype], or null when the entry
     * is absent or expired.
     */
    fun lookup(
        domain: String,
        qtype: String,
    ): List<String>?
}

/** A [DnsCache] that always returns null (no cache). */
object NoOpDnsCache : DnsCache {
    override fun lookup(
        domain: String,
        qtype: String,
    ): List<String>? = null
}

/**
 * Performs strict encrypted DNS resolution for proxy-class domains.
 *
 * Resolution order (per [FailoverPolicy]):
 *  1. Check [cache] — serve from cache on hit.
 *  2. Try [FailoverPolicy.primary] via the active outbound (`""`).
 *  3. Try each [FailoverPolicy.secondaries] via the active outbound.
 *  4. Try each [FailoverPolicy.fallbackOutbounds] with [FailoverPolicy.primary] and secondaries.
 *  5. Return [StrictDnsResult.StrictFailure] — callers translate to SERVFAIL / blocked.
 *
 * No plaintext system-DNS branch exists. The type system prevents adding one.
 *
 * @param transport Performs individual resolver queries.
 * @param cache     Optional cache consulted before live attempts.
 */
class StrictDnsResolver(
    private val transport: EncryptedResolverTransport,
    private val cache: DnsCache = NoOpDnsCache,
) {
    /**
     * Resolve [domain] / [qtype] under [policy], returning [StrictDnsResult.Success]
     * or [StrictDnsResult.StrictFailure].
     */
    fun resolve(
        domain: String,
        qtype: String,
        policy: FailoverPolicy,
    ): StrictDnsResult {
        cache.lookup(domain, qtype)?.let { cached ->
            return StrictDnsResult.Success(
                addresses = cached,
                outboundUsed = "",
                fromCache = true,
            )
        }

        val activeOutbound: OutboundId = ""
        val allEndpoints = listOf(policy.primary) + policy.secondaries

        for (endpoint in allEndpoints) {
            transport.resolve(domain, qtype, endpoint, activeOutbound)?.let { addresses ->
                return StrictDnsResult.Success(addresses = addresses, outboundUsed = activeOutbound)
            }
        }

        for (fallbackOutbound in policy.fallbackOutbounds) {
            for (endpoint in allEndpoints) {
                transport.resolve(domain, qtype, endpoint, fallbackOutbound)?.let { addresses ->
                    return StrictDnsResult.Success(
                        addresses = addresses,
                        outboundUsed = fallbackOutbound,
                    )
                }
            }
        }

        return StrictDnsResult.StrictFailure
    }
}
