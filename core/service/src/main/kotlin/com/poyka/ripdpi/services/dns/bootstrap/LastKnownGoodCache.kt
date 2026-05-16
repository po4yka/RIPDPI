package com.poyka.ripdpi.services.dns.bootstrap

/** Tags the origin of a [CacheEntry] so callers can distinguish bootstrap-derived answers. */
enum class CacheEntrySource {
    /** The entry was produced by a bootstrap resolver, not by a live tunnel resolver. */
    BootstrapDerived,
}

/**
 * A single last-known-good cache record.
 *
 * @param addresses   Resolved addresses stored in this entry.
 * @param source      Always [CacheEntrySource.BootstrapDerived] for entries from this cache.
 * @param expiresAtMs Epoch-millisecond timestamp after which this entry must not be served.
 */
data class CacheEntry(
    val addresses: List<String>,
    val source: CacheEntrySource,
    val expiresAtMs: Long,
)

/**
 * Bounded TTL last-known-good cache for bootstrap-resolved addresses.
 *
 * All stored entries carry [CacheEntrySource.BootstrapDerived] so downstream
 * consumers can identify that addresses originated from the bootstrap plane.
 *
 * @param maxTtlMs Maximum time-to-live in milliseconds. Entries are not served
 *                 after [maxTtlMs] has elapsed since they were stored.
 */
class LastKnownGoodCache(
    val maxTtlMs: Long,
) {
    private val entries = mutableMapOf<String, CacheEntry>()

    private fun key(
        name: String,
        qtype: String,
    ) = "$name/$qtype"

    /**
     * Store [addresses] for the [name] / [qtype] pair.
     *
     * The entry is tagged [CacheEntrySource.BootstrapDerived] and expires after [maxTtlMs].
     */
    fun store(
        name: String,
        qtype: String,
        addresses: List<String>,
    ) {
        entries[key(name, qtype)] =
            CacheEntry(
                addresses = addresses,
                source = CacheEntrySource.BootstrapDerived,
                expiresAtMs = System.currentTimeMillis() + maxTtlMs,
            )
    }

    /**
     * Return a valid [CacheEntry] for [name] / [qtype], or null when absent or expired.
     */
    fun lookup(
        name: String,
        qtype: String,
    ): CacheEntry? {
        val entry = entries[key(name, qtype)] ?: return null
        return if (System.currentTimeMillis() < entry.expiresAtMs) entry else null
    }
}
