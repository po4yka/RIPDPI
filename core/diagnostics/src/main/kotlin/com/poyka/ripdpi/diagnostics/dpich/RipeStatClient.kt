package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class RipeStatClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val retryDelayMs: Long = DefaultRetryDelayMs,
) : RipeStatPrefixSource {
    override suspend fun announcedPrefixes(asn: Int): List<String> {
        val first = requestPrefixes(asn)
        if (first.code != RateLimitCode) {
            return first.prefixesOrThrow(asn)
        }
        delay(retryDelayMs)
        val retry = requestPrefixes(asn)
        if (retry.code == RateLimitCode) {
            throw IOException("RIPE Stat rate limited for AS$asn")
        }
        return retry.prefixesOrThrow(asn)
    }

    private suspend fun requestPrefixes(asn: Int): RipeStatHttpResult =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$RipeStatAnnouncedPrefixesUrl$asn")
                    .get()
                    .build()
            httpClient.newCall(request).execute().use { response ->
                RipeStatHttpResult(
                    code = response.code,
                    body = response.body.string(),
                )
            }
        }

    private fun RipeStatHttpResult.prefixesOrThrow(asn: Int): List<String> {
        if (code !in SuccessCodes) {
            throw IOException("RIPE Stat announced-prefixes failed for AS$asn: HTTP $code")
        }
        return json
            .decodeFromString(RipeStatResponse.serializer(), body)
            .data
            .prefixes
            .map(RipeStatPrefix::prefix)
    }

    private companion object {
        private const val RipeStatAnnouncedPrefixesUrl =
            "https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS"
        private const val RateLimitCode = 429
        private const val DefaultRetryDelayMs = 1_000L
        private val SuccessCodes = 200..299
    }
}

private data class RipeStatHttpResult(
    val code: Int,
    val body: String,
)

@Serializable
private data class RipeStatResponse(
    val data: RipeStatData,
)

@Serializable
private data class RipeStatData(
    val prefixes: List<RipeStatPrefix> = emptyList(),
)

@Serializable
private data class RipeStatPrefix(
    @SerialName("prefix")
    val prefix: String,
)
