package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

enum class CidrWhitelistVerdict {
    OK,
    CIDR_WHITELIST_DETECTED,
    NO_INTERNET,
}

enum class CidrWhitelistUrlGroup {
    WHITELISTED,
    REGULAR,
}

data class CidrWhitelistProbeTrace(
    val url: String,
    val group: CidrWhitelistUrlGroup,
    val ok: Boolean,
    val statusCode: Int? = null,
    val latencyMs: Long,
    val error: String? = null,
)

data class CidrWhitelistDetectionResult(
    val verdict: CidrWhitelistVerdict,
    val traces: List<CidrWhitelistProbeTrace>,
)

fun interface CidrWhitelistUrlProbe {
    suspend fun probe(
        url: String,
        group: CidrWhitelistUrlGroup,
        timeoutMs: Long,
    ): CidrWhitelistProbeTrace
}

class CidrWhitelistDetector(
    private val whitelistedUrls: List<String>,
    private val regularUrls: List<String>,
    private val urlProbe: CidrWhitelistUrlProbe = OkHttpCidrWhitelistUrlProbe(),
) {
    suspend fun detect(timeoutMs: Long = DefaultTimeoutMs): CidrWhitelistDetectionResult =
        coroutineScope {
            val traces = Collections.synchronizedList(mutableListOf<CidrWhitelistProbeTrace>())
            val regularSuccess = CompletableDeferred<Unit>()
            val urlGroups =
                buildList {
                    addAll(whitelistedUrls.map { url -> CidrWhitelistUrlGroup.WHITELISTED to url })
                    addAll(regularUrls.map { url -> CidrWhitelistUrlGroup.REGULAR to url })
                }
            val probes =
                urlGroups.map { (group, url) ->
                    async {
                        val trace = urlProbe.probe(url = url, group = group, timeoutMs = timeoutMs)
                        traces += trace
                        if (trace.group == CidrWhitelistUrlGroup.REGULAR && trace.ok) {
                            regularSuccess.complete(Unit)
                        }
                        trace
                    }
                }
            if (probes.isEmpty()) {
                return@coroutineScope CidrWhitelistDetectionResult(
                    verdict = CidrWhitelistVerdict.NO_INTERNET,
                    traces = emptyList(),
                )
            }

            val allDone = async { probes.awaitAll() }
            val regularWon =
                select {
                    regularSuccess.onAwait { true }
                    allDone.onAwait { false }
                }
            if (regularWon) {
                probes.forEach { probe -> probe.cancel() }
                allDone.cancel()
            }
            runCatching { probes.awaitAll() }
                .onFailure { error ->
                    if (error is CancellationException && !regularWon) throw error
                }
            traces.toList().toDetectionResult(forceOk = regularWon)
        }

    private fun List<CidrWhitelistProbeTrace>.toDetectionResult(forceOk: Boolean): CidrWhitelistDetectionResult {
        val regularOk = any { trace -> trace.group == CidrWhitelistUrlGroup.REGULAR && trace.ok }
        val whitelistedOk = any { trace -> trace.group == CidrWhitelistUrlGroup.WHITELISTED && trace.ok }
        val verdict =
            when {
                forceOk || regularOk -> CidrWhitelistVerdict.OK
                whitelistedOk -> CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED
                else -> CidrWhitelistVerdict.NO_INTERNET
            }
        return CidrWhitelistDetectionResult(verdict = verdict, traces = this)
    }

    private companion object {
        private const val DefaultTimeoutMs = 8_000L
    }
}

class OkHttpCidrWhitelistUrlProbe(
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CidrWhitelistUrlProbe {
    override suspend fun probe(
        url: String,
        group: CidrWhitelistUrlGroup,
        timeoutMs: Long,
    ): CidrWhitelistProbeTrace =
        withContext(ioDispatcher) {
            var latencyMs = 0L
            try {
                val client =
                    clientBuilder {
                        followRedirects(false)
                        followSslRedirects(false)
                        connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    }
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .head()
                        .build()
                var statusCode = 0
                latencyMs =
                    measureTimeMillis {
                        client.newCall(request).execute().use { response ->
                            statusCode = response.code
                        }
                    }
                CidrWhitelistProbeTrace(
                    url = url,
                    group = group,
                    ok = statusCode in SuccessStatusRange,
                    statusCode = statusCode,
                    latencyMs = latencyMs,
                    error = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedIOException) {
                failureTrace(url = url, group = group, latencyMs = latencyMs, error = error)
            } catch (error: IOException) {
                failureTrace(url = url, group = group, latencyMs = latencyMs, error = error)
            } catch (error: IllegalArgumentException) {
                failureTrace(url = url, group = group, latencyMs = latencyMs, error = error)
            }
        }

    private fun failureTrace(
        url: String,
        group: CidrWhitelistUrlGroup,
        latencyMs: Long,
        error: Exception,
    ): CidrWhitelistProbeTrace =
        CidrWhitelistProbeTrace(
            url = url,
            group = group,
            ok = false,
            statusCode = null,
            latencyMs = latencyMs,
            error = error.message ?: error.javaClass.simpleName,
        )

    private companion object {
        private val SuccessStatusRange = 200..399
    }
}
