@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.community

import android.content.Context
import androidx.core.content.edit
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityComparisonStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val json = RipDpiJson

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
            prefs.edit {
                putString(KEY_CACHED_STATS, json.encodeToString(stats))
                putLong(KEY_STATS_CACHED_AT, System.currentTimeMillis())
            }
        }

        fun clear() {
            prefs.edit { clear() }
        }

        companion object {
            private const val PREFS_NAME = "community_comparison"
            private const val KEY_CACHED_STATS = "cached_stats"
            private const val KEY_STATS_CACHED_AT = "stats_cached_at"
            private const val CACHE_TTL_MS = 3600_000L
        }
    }
