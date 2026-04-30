package com.poyka.ripdpi.core.detection.community

import com.poyka.ripdpi.core.detection.DetectionHistoryStore
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class CommunityComparisonClient internal constructor(
    private val client: OkHttpClient,
    private val dispatchers: AppCoroutineDispatchers,
) {
    constructor(dispatchers: AppCoroutineDispatchers) : this(defaultClient(), dispatchers)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchStats(githubUrl: String): Result<CommunityStats> =
        withContext(dispatchers.io) {
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
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    }
                    val responseBody = response.body.string()
                    val body =
                        responseBody.takeIf(String::isNotBlank)
                            ?: return@withContext Result.failure(
                                IllegalStateException("Community stats response body was empty"),
                            )
                    Result.success(json.decodeFromString<CommunityStats>(body))
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
        }

    companion object {
        const val DEFAULT_STATS_URL =
            "https://raw.githubusercontent.com/AnyKnew/ripdpi-community-stats/main/stats.json"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()

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
