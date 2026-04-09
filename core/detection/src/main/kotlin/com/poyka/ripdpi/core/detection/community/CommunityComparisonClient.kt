package com.poyka.ripdpi.core.detection.community

import com.poyka.ripdpi.core.detection.DetectionHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class CommunityComparisonClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    suspend fun fetchStats(githubUrl: String): Result<CommunityStats> =
        withContext(Dispatchers.IO) {
            if (githubUrl.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Community stats URL not configured"),
                )
            }
            try {
                val request =
                    Request
                        .Builder()
                        .url(githubUrl)
                        .get()
                        .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body =
                    response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))
                Result.success(json.decodeFromString<CommunityStats>(body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    companion object {
        const val DEFAULT_STATS_URL =
            "https://raw.githubusercontent.com/AnyKnew/ripdpi-community-stats/main/stats.json"

        fun computeLocalStats(historyStore: DetectionHistoryStore): CommunityStats {
            val entries = historyStore.latestEntries(count = 50)
            if (entries.isEmpty()) return CommunityStats()

            val verdictDist = entries.groupingBy { it.verdict }.eachCount()
            val avgScore = entries.map { it.stealthScore }.average()

            return CommunityStats(
                totalReports = entries.size,
                verdictDistribution = verdictDist,
                averageStealthScore = avgScore,
                isLocalOnly = true,
            )
        }
    }
}
