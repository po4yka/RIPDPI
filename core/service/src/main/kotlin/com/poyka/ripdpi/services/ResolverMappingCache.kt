package com.poyka.ripdpi.services

import javax.inject.Inject
import javax.inject.Singleton

internal fun interface ResolverMappingClock {
    fun nowMs(): Long
}

@Singleton
internal class ResolverMappingCache(
    private val clock: ResolverMappingClock,
) {
    @Inject
    constructor() : this(ResolverMappingClock { System.currentTimeMillis() })

    private val entries = linkedMapOf<ResolverMappingCacheKey, ResolverMappingCacheEntry>()

    fun put(
        host: String,
        networkScopeKey: String,
        mapping: ResolvedMapping,
        ttlMs: Long,
    ) {
        if (ttlMs <= 0) {
            return
        }
        entries[ResolverMappingCacheKey.create(host, networkScopeKey)] =
            ResolverMappingCacheEntry(mapping = mapping, expiresAtMs = clock.nowMs() + ttlMs)
    }

    fun get(
        host: String,
        networkScopeKey: String,
    ): ResolvedMapping? {
        val key = ResolverMappingCacheKey.create(host, networkScopeKey)
        val entry = entries[key]
        val expired = entry != null && entry.expiresAtMs <= clock.nowMs()
        if (expired) {
            entries.remove(key)
        }
        return entry?.takeUnless { expired }?.mapping
    }
}

private class ResolverMappingCacheKey private constructor(
    private val host: String,
    private val networkScopeKey: String,
) {
    companion object {
        fun create(
            host: String,
            networkScopeKey: String,
        ): ResolverMappingCacheKey =
            ResolverMappingCacheKey(
                host = host.trim().trimEnd('.').lowercase(),
                networkScopeKey = networkScopeKey.trim(),
            )
    }

    override fun equals(other: Any?): Boolean =
        other is ResolverMappingCacheKey &&
            host == other.host &&
            networkScopeKey == other.networkScopeKey

    override fun hashCode(): Int = 31 * host.hashCode() + networkScopeKey.hashCode()
}

private data class ResolverMappingCacheEntry(
    val mapping: ResolvedMapping,
    val expiresAtMs: Long,
)
