package com.poyka.ripdpi.core.detection.community

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CommunityComparisonStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getCachedStats(): CommunityStats? {
        val raw = prefs.getString(KEY_CACHED_STATS, null) ?: return null
        val cachedAt = prefs.getLong(KEY_STATS_CACHED_AT, 0)
        if (System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) return null
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun cacheStats(stats: CommunityStats) {
        prefs
            .edit()
            .putString(KEY_CACHED_STATS, json.encodeToString(stats))
            .putLong(KEY_STATS_CACHED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "community_comparison"
        private const val KEY_CACHED_STATS = "cached_stats"
        private const val KEY_STATS_CACHED_AT = "stats_cached_at"
        private const val CACHE_TTL_MS = 3600_000L
    }
}
