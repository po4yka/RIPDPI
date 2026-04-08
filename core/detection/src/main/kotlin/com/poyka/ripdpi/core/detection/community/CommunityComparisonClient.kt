package com.poyka.ripdpi.core.detection.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CommunityComparisonClient(
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    suspend fun submit(submission: CommunitySubmission): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Community API URL not configured"))
            }
            try {
                val body =
                    json
                        .encodeToString(submission)
                        .toRequestBody("application/json".toMediaType())
                val request =
                    Request
                        .Builder()
                        .url("$baseUrl/api/v1/detection/submit")
                        .post(body)
                        .header("Content-Type", "application/json")
                        .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchStats(): Result<CommunityStats> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Community API URL not configured"))
            }
            try {
                val request =
                    Request
                        .Builder()
                        .url("$baseUrl/api/v1/detection/stats")
                        .get()
                        .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val responseBody =
                    response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))
                Result.success(json.decodeFromString<CommunityStats>(responseBody))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
