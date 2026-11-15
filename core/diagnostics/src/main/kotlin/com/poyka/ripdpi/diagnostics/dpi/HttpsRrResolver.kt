package com.poyka.ripdpi.diagnostics.dpi

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import com.poyka.ripdpi.data.diagnostics.DnsMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xbill.DNS.Type
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class HttpsRrRecord(
    val priority: Int,
    val targetName: String,
    val echConfig: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpsRrRecord &&
            priority == other.priority &&
            targetName == other.targetName &&
            echConfig.contentEqualsNullable(other.echConfig)

    override fun hashCode(): Int = 31 * (31 * priority + targetName.hashCode()) + (echConfig?.contentHashCode() ?: 0)
}

enum class HttpsRrOutcome {
    ANSWER,
    NODATA,
    NXDOMAIN,
    DNS_ERROR,
    TRUNCATED,
    MALFORMED,
    TIMEOUT,
    TRANSPORT_ERROR,
    HTTP_ERROR,
    PLATFORM_UNSUPPORTED,
    NOT_OBSERVED,
    ALIAS_LIMIT,
    SERVICE_UNAVAILABLE,
}

data class HttpsRrResponse(
    val outcome: HttpsRrOutcome,
    val records: List<HttpsRrRecord> = emptyList(),
    val rcode: Int? = null,
    val httpStatus: Int? = null,
)

enum class HttpsRrSource {
    DOH,
    SYSTEM,
}

data class HttpsRrLookupAttempt(
    val source: HttpsRrSource,
    val outcome: HttpsRrOutcome,
    val rcode: Int?,
    val httpStatus: Int?,
)

data class HttpsRrLookup(
    val record: HttpsRrRecord?,
    val attempts: List<HttpsRrLookupAttempt>,
) {
    val receivedRecords: Boolean
        get() = attempts.any { it.outcome == HttpsRrOutcome.ANSWER }

    val configurationAbsent: Boolean
        get() = attempts.groupBy { it.source }.values.any { it.last().outcome in noConfigurationOutcomes }

    private companion object {
        val noConfigurationOutcomes =
            setOf(
                HttpsRrOutcome.ANSWER,
                HttpsRrOutcome.NODATA,
                HttpsRrOutcome.NXDOMAIN,
                HttpsRrOutcome.SERVICE_UNAVAILABLE,
            )
    }
}

fun interface HttpsRrQuery {
    suspend fun query(host: String): HttpsRrResponse
}

class HttpsRrResolver(
    private val query: HttpsRrQuery = DohWireHttpsRrQuery(),
    private val fallbackQuery: HttpsRrQuery? = AndroidSystemHttpsRrQuery(),
) {
    suspend fun fetch(host: String): HttpsRrLookup {
        val attempts = mutableListOf<HttpsRrLookupAttempt>()
        val primary = query.followAliases(host, HttpsRrSource.DOH, attempts)
        val selected = primary.records.selectEchRecord()
        if (selected != null || primary.outcome in terminalOutcomes || fallbackQuery == null) {
            return HttpsRrLookup(selected, attempts)
        }
        val fallback = fallbackQuery.followAliases(host, HttpsRrSource.SYSTEM, attempts)
        return HttpsRrLookup(fallback.records.selectEchRecord(), attempts)
    }

    private fun List<HttpsRrRecord>.selectEchRecord(): HttpsRrRecord? =
        filter { record -> !record.echConfig.isNullOrEmptyBytes() }.minByOrNull { record -> record.priority }

    private suspend fun HttpsRrQuery.followAliases(
        host: String,
        source: HttpsRrSource,
        attempts: MutableList<HttpsRrLookupAttempt>,
    ): HttpsRrResponse {
        var name = host.trimEnd('.').lowercase(Locale.ROOT)
        val visited = mutableSetOf<String>()
        while (visited.size <= MaxAliasHops && visited.add(name)) {
            val response = attempt(name)
            attempts += HttpsRrLookupAttempt(source, response.outcome, response.rcode, response.httpStatus)
            val alias = response.records.firstOrNull { it.priority == 0 } ?: return response
            name = alias.targetName.trimEnd('.').lowercase(Locale.ROOT)
            if (name.isEmpty()) return stopAliases(source, HttpsRrOutcome.SERVICE_UNAVAILABLE, attempts)
        }
        return stopAliases(source, HttpsRrOutcome.ALIAS_LIMIT, attempts)
    }

    private suspend fun HttpsRrQuery.attempt(host: String): HttpsRrResponse =
        try {
            query(host)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            error.toHttpsRrFailure()
        } catch (_: IllegalArgumentException) {
            HttpsRrResponse(HttpsRrOutcome.MALFORMED)
        } catch (_: IllegalStateException) {
            HttpsRrResponse(HttpsRrOutcome.TRANSPORT_ERROR)
        }

    private fun stopAliases(
        source: HttpsRrSource,
        outcome: HttpsRrOutcome,
        attempts: MutableList<HttpsRrLookupAttempt>,
    ): HttpsRrResponse {
        attempts += HttpsRrLookupAttempt(source, outcome, rcode = null, httpStatus = null)
        return HttpsRrResponse(outcome)
    }

    private companion object {
        private const val MaxAliasHops = 3
        val terminalOutcomes = setOf(HttpsRrOutcome.SERVICE_UNAVAILABLE, HttpsRrOutcome.ALIAS_LIMIT)
    }
}

private fun IOException.toHttpsRrFailure(): HttpsRrResponse =
    HttpsRrResponse(if (this is InterruptedIOException) HttpsRrOutcome.TIMEOUT else HttpsRrOutcome.TRANSPORT_ERROR)

class DohWireHttpsRrQuery(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(DefaultTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(DefaultTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(DefaultTimeoutMs * 2, TimeUnit.MILLISECONDS)
            .build(),
    private val endpoint: String = DefaultDohEndpoint,
) : HttpsRrQuery {
    override suspend fun query(host: String): HttpsRrResponse =
        suspendCancellableCoroutine { continuation ->
            val transactionId = DnsWireBuilder.randomTransactionId()
            val query = HttpsDnsWireBuilder.buildQuery(host, transactionId)
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post(query.toRequestBody(DnsMessageMediaType))
                    .header("Accept", DnsMessageContentType)
                    .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resume(e.toHttpsRrFailure())
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            val result =
                                response.use {
                                    if (it.isSuccessful) {
                                        HttpsDnsWireParser.parseResponse(it.readDnsWireBody(), query)
                                    } else {
                                        HttpsRrResponse(HttpsRrOutcome.HTTP_ERROR, httpStatus = it.code)
                                    }
                                }
                            continuation.resume(result)
                        } catch (_: MalformedDnsHttpResponse) {
                            continuation.resume(HttpsRrResponse(HttpsRrOutcome.MALFORMED))
                        } catch (error: IOException) {
                            continuation.resume(error.toHttpsRrFailure())
                        }
                    }
                },
            )
        }

    private companion object {
        private const val DefaultTimeoutMs = 3_000L
        private const val DefaultDohEndpoint = "https://cloudflare-dns.com/dns-query"
        private const val DnsMessageContentType = "application/dns-message"
        private val DnsMessageMediaType = DnsMessageContentType.toMediaType()
    }
}

class AndroidSystemHttpsRrQuery(
    private val network: Network? = null,
    private val resolverProvider: (() -> DnsResolver)? = null,
    private val executor: Executor = Executor { command -> command.run() },
) : HttpsRrQuery {
    @SuppressLint("NewApi", "WrongConstant")
    override suspend fun query(host: String): HttpsRrResponse {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return HttpsRrResponse(HttpsRrOutcome.PLATFORM_UNSUPPORTED)
        }
        // TODO(author): DnsResolver.getInstance() deprecated; needs Context plumbing
        @Suppress("DEPRECATION")
        val resolver = resolverProvider?.invoke() ?: DnsResolver.getInstance()
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            resolver.rawQuery(
                network,
                host,
                DnsResolver.CLASS_IN,
                HttpsDnsWireBuilder.HttpsRecordType,
                DnsResolver.FLAG_EMPTY,
                executor,
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(
                        answer: ByteArray,
                        rcode: Int,
                    ) {
                        val result = HttpsDnsWireParser.parseSystemResponse(answer, host)
                        continuation.resume(
                            if (result.rcode != null && result.rcode != rcode) {
                                HttpsRrResponse(HttpsRrOutcome.MALFORMED)
                            } else {
                                result
                            },
                        )
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.resume(HttpsRrResponse(androidLookupFailure(error.code, error.cause)))
                    }
                },
            )
        }
    }
}

internal fun androidLookupFailure(
    code: Int,
    cause: Throwable?,
): HttpsRrOutcome =
    when {
        code == DnsResolver.ERROR_PARSE -> HttpsRrOutcome.MALFORMED
        cause is InterruptedIOException -> HttpsRrOutcome.TIMEOUT
        cause is ErrnoException && cause.errno == OsConstants.ETIMEDOUT -> HttpsRrOutcome.TIMEOUT
        else -> HttpsRrOutcome.TRANSPORT_ERROR
    }

object HttpsDnsWireBuilder {
    const val HttpsRecordType = Type.HTTPS

    fun buildQuery(
        domain: String,
        transactionId: Int = DnsWireBuilder.randomTransactionId(),
    ): ByteArray = DnsMessages.query(domain, Type.HTTPS, transactionId)
}

enum class EchProbeVerdict {
    ECH_OK,
    ECH_REJECTED,
    ECH_NO_CONFIG,
    ECH_LOOKUP_INCONCLUSIVE,
    ECH_UNKNOWN_KEY,
    ECH_NETWORK_BLOCK,
}

data class EchProbeResult(
    val target: String,
    val verdict: EchProbeVerdict,
    val httpsRrFetched: Boolean,
    val echConfigBytesB64: String?,
    val tlsLatencyMs: Long?,
    val negotiatedEch: Boolean,
    val errorDetail: String?,
    val vanillaTlsOk: Boolean? = null,
    val httpsLookups: List<HttpsRrLookupAttempt> = emptyList(),
) {
    val hasMeasurement: Boolean
        get() = verdict != EchProbeVerdict.ECH_NO_CONFIG && verdict != EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE

    val echSucceededWhenBaselineTlsFailed: Boolean = vanillaTlsOk == false && verdict == EchProbeVerdict.ECH_OK
}

data class EchTlsHandshakeResult(
    val negotiatedEch: Boolean,
    val latencyMs: Long?,
)

interface EchTlsHandshake {
    suspend fun connect(
        target: String,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult
}

class EchReadinessProbe(
    private val resolver: HttpsRrResolver = HttpsRrResolver(),
    private val tlsHandshake: EchTlsHandshake = UnsupportedEchTlsHandshake,
    private val concurrency: Int = DefaultConcurrency,
) {
    suspend fun check(
        target: String,
        vanillaTlsOk: Boolean? = null,
    ): EchProbeResult {
        val lookup = resolver.fetch(target)
        val configBytes = lookup.record?.echConfig
        if (configBytes == null) {
            return EchProbeResult(
                target = target,
                verdict =
                    if (lookup.configurationAbsent) {
                        EchProbeVerdict.ECH_NO_CONFIG
                    } else {
                        EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE
                    },
                httpsRrFetched = lookup.receivedRecords,
                echConfigBytesB64 = null,
                tlsLatencyMs = null,
                negotiatedEch = false,
                errorDetail = null,
                vanillaTlsOk = vanillaTlsOk,
                httpsLookups = lookup.attempts,
            )
        }

        return try {
            val result = tlsHandshake.connect(target, configBytes)
            EchProbeResult(
                target = target,
                verdict = if (result.negotiatedEch) EchProbeVerdict.ECH_OK else EchProbeVerdict.ECH_REJECTED,
                httpsRrFetched = true,
                echConfigBytesB64 = Base64.getEncoder().encodeToString(configBytes),
                tlsLatencyMs = result.latencyMs,
                negotiatedEch = result.negotiatedEch,
                errorDetail = null,
                vanillaTlsOk = vanillaTlsOk,
                httpsLookups = lookup.attempts,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, lookup.attempts, error)
        } catch (error: IllegalArgumentException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, lookup.attempts, error)
        } catch (error: IllegalStateException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, lookup.attempts, error)
        } catch (error: UnsupportedOperationException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, lookup.attempts, error)
        }
    }

    private fun buildFailedEchProbeResult(
        target: String,
        configBytes: ByteArray,
        vanillaTlsOk: Boolean?,
        attempts: List<HttpsRrLookupAttempt>,
        error: Exception,
    ): EchProbeResult {
        val verdict = classifyEchFailure(error)
        return EchProbeResult(
            target = target,
            verdict = verdict,
            httpsRrFetched = true,
            echConfigBytesB64 = Base64.getEncoder().encodeToString(configBytes),
            tlsLatencyMs = null,
            negotiatedEch = false,
            errorDetail = error.message ?: error.javaClass.simpleName,
            vanillaTlsOk = vanillaTlsOk,
            httpsLookups = attempts,
        )
    }

    suspend fun checkAll(
        targets: List<String>,
        vanillaTlsByTarget: Map<String, Boolean> = emptyMap(),
    ): List<EchProbeResult> =
        coroutineScope {
            val permits = Semaphore(concurrency.coerceIn(MinConcurrency, DefaultConcurrency))
            val checks =
                targets.map { target ->
                    async {
                        permits.withPermit {
                            check(
                                target = target,
                                vanillaTlsOk = vanillaTlsByTarget[target],
                            )
                        }
                    }
                }
            checks.awaitAll()
        }

    private fun classifyEchFailure(error: Exception): EchProbeVerdict {
        val message = (error.message ?: "").lowercase()
        return when {
            "ech_required" in message -> {
                EchProbeVerdict.ECH_UNKNOWN_KEY
            }

            "encrypted_client_hello" in message -> {
                EchProbeVerdict.ECH_REJECTED
            }

            error is SocketTimeoutException || "connect" in message || "timeout" in message -> {
                EchProbeVerdict.ECH_NETWORK_BLOCK
            }

            else -> {
                EchProbeVerdict.ECH_REJECTED
            }
        }
    }

    private companion object {
        private const val MinConcurrency = 1
        private const val DefaultConcurrency = 4
    }
}

private object UnsupportedEchTlsHandshake : EchTlsHandshake {
    override suspend fun connect(
        target: String,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult = throw UnsupportedOperationException("ECH TLS handshake bridge is not available")
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

private fun ByteArray?.isNullOrEmptyBytes(): Boolean = this == null || isEmpty()
