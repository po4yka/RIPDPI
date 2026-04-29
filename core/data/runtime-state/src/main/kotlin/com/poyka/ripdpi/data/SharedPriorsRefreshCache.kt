package com.poyka.ripdpi.data

// Bookkeeping for the shared-priors refresh worker (P4.4.4, ADR-011).
// Stored in EncryptedSharedPreferences so the 24h cooldown survives
// process restarts; without it, every reboot would trigger a fresh
// network round-trip even when the manifest has not changed since the
// last apply.
data class SharedPriorsRefreshState(
    val lastRefreshUnixMs: Long,
    val lastModifiedHeader: String?,
)

interface SharedPriorsRefreshCache {
    suspend fun load(): SharedPriorsRefreshState?

    suspend fun save(state: SharedPriorsRefreshState)
}
