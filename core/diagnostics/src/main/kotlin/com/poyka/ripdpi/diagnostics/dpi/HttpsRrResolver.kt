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
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class HttpsRrRecord(
    val priority: Int,
    val targetName: String,
    val echConfig: ByteArray?,
    val ownerName: String? = null,
    val port: Int = HttpsDefaultPort,
    val supportsTcp: Boolean = true,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpsRrRecord &&
            priority == other.priority &&
            targetName == other.targetName &&
            ownerName == other.ownerName &&
            port == other.port &&
            supportsTcp == other.supportsTcp &&
            echConfig.contentEqualsNullable(other.echConfig)

    override fun hashCode(): Int {
        var hash = priority
        hash = 31 * hash + targetName.hashCode()
        hash = 31 * hash + (ownerName?.hashCode() ?: 0)
        hash = 31 * hash + port
        hash = 31 * hash + supportsTcp.hashCode()
        return 31 * hash + (echConfig?.contentHashCode() ?: 0)
    }
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
    val candidates: List<HttpsRrRecord>,
    val hasIncompatibleBinding: Boolean,
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
        val primaryBindings = primary.records.echBindings()
        val primaryCandidates = primaryBindings.filter(HttpsRrRecord::supportsTcp)
        if (primaryCandidates.isNotEmpty() || primary.outcome in terminalOutcomes || fallbackQuery == null) {
            return HttpsRrLookup(primaryCandidates, primaryBindings.any { !it.supportsTcp }, attempts)
        }
        val fallback = fallbackQuery.followAliases(host, HttpsRrSource.SYSTEM, attempts)
        val fallbackBindings = fallback.records.echBindings()
        return HttpsRrLookup(
            fallbackBindings.filter(HttpsRrRecord::supportsTcp),
            (primaryBindings + fallbackBindings).any { !it.supportsTcp },
            attempts,
        )
    }

    private fun List<HttpsRrRecord>.echBindings(): List<HttpsRrRecord> =
        filter { record -> !record.echConfig.isNullOrEmptyBytes() }.sortedBy { it.priority }

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
            val alias = response.records.firstOrNull { it.priority == 0 }
            if (alias == null) {
                return response.copy(
                    records =
                        response.records.map { record ->
                            record.copy(ownerName = record.ownerName ?: name)
                        },
                )
            }
            name = alias.targetName.trimEnd('.').lowercase(Locale.ROOT)
            if (name.isEmpty()) break
        }
        val outcome = if (name.isEmpty()) HttpsRrOutcome.SERVICE_UNAVAILABLE else HttpsRrOutcome.ALIAS_LIMIT
        return stopAliases(source, outcome, attempts)
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
            val query = HttpsDnsWireBuilder.buildQuery(host, transactionId = DohTransactionId)
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

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

private fun ByteArray?.isNullOrEmptyBytes(): Boolean = this == null || isEmpty()

internal const val HttpsDefaultPort = 443
