package com.poyka.ripdpi.data

// Single ECH cache entry persisted across process restarts (Phase 3 of
// ECH rotation architecture note). The native CdnEchUpdater holds the in-memory monotonic anchor;
// this carries the wall-clock timestamp so the entry can round-trip
// through platform storage without losing TTL fidelity.
data class PersistedEchEntry(
    val configBytes: ByteArray,
    val fetchedAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedEchEntry) return false
        return fetchedAtUnixMs == other.fetchedAtUnixMs && configBytes.contentEquals(other.configBytes)
    }

    override fun hashCode(): Int = 31 * configBytes.contentHashCode() + fetchedAtUnixMs.hashCode()
}

// Hilt-injectable handle for the durable ECH cache. The production
// implementation is backed by EncryptedSharedPreferences; tests can swap
// in an in-memory fake without pulling Android Keystore into the test
// graph.
interface CdnEchPersistedCache {
    suspend fun load(): PersistedEchEntry?

    suspend fun save(entry: PersistedEchEntry)

    suspend fun clear()
}
