package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for the fastest observed encrypted-DNS resolver per
 * (host, networkScopeKey) pair.
 *
 * The resolver failover controller learns which path survives for a given
 * network scope, but makes no distinction between different target hosts.
 * This cache fills that gap: once a host's DNS query has succeeded on a
 * specific resolver for the current network scope, subsequent queries for
 * the same host reuse that resolver without going through the full candidate
 * plan.
 *
 * TTL semantics: entries expire after [ttlMs] milliseconds of wall-clock time
 * so that a resolver that was fast an hour ago can be re-evaluated after
 * conditions change. The default is 30 minutes, matching typical DNS TTLs.
 *
 * Thread-safety: all public operations are safe to call from multiple threads
 * (backed by [ConcurrentHashMap] with simple per-entry expiry).
 */
class FastestResolverCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_TTL_MS: Long = 30L * 60L * 1_000L // 30 minutes
    }

    private data class CacheKey(
        val host: String,
        val networkScopeKey: String,
    )

    private data class CachedEntry(
        val candidate: EncryptedDnsPathCandidate,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<CacheKey, CachedEntry>()

    /**
     * Returns the cached fastest resolver for [host] on [networkScopeKey],
     * or `null` if no valid (non-expired) entry exists.
     */
    fun get(
        host: String,
        networkScopeKey: String,
    ): EncryptedDnsPathCandidate? {
        val key = CacheKey(host, networkScopeKey)
        val entry = cache[key] ?: return null
        if (clock() >= entry.expiresAt) {
            cache.remove(key)
            return null
        }
        return entry.candidate
    }

    /**
     * Records [candidate] as the fastest resolver for [host] on [networkScopeKey].
     */
    fun put(
        host: String,
        networkScopeKey: String,
        candidate: EncryptedDnsPathCandidate,
    ) {
        val key = CacheKey(host, networkScopeKey)
        cache[key] = CachedEntry(candidate = candidate, expiresAt = clock() + ttlMs)
    }

    /**
     * Removes all entries for [host], optionally scoped to a specific [networkScopeKey].
     * Pass `null` for [networkScopeKey] to clear all network scopes for that host.
     */
    fun invalidate(
        host: String,
        networkScopeKey: String? = null,
    ) {
        if (networkScopeKey != null) {
            cache.remove(CacheKey(host, networkScopeKey))
        } else {
            cache.keys.removeIf { it.host == host }
        }
    }

    /**
     * Removes all entries for [networkScopeKey] regardless of host.
     * Call this when the network scope changes (e.g. Wi-Fi handover) so that
     * host-specific resolver preferences are re-evaluated on the new network.
     */
    fun invalidateScope(networkScopeKey: String) {
        cache.keys.removeIf { it.networkScopeKey == networkScopeKey }
    }

    /** Removes all entries regardless of host or scope. */
    fun invalidateAll() {
        cache.clear()
    }

    /** Returns the number of non-expired entries currently held. */
    fun size(): Int {
        val now = clock()
        return cache.values.count { it.expiresAt > now }
    }
}
