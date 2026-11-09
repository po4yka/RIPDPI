package com.poyka.ripdpi.diagnostics.dpi

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
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
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

fun interface HttpsRrQuery {
    suspend fun query(host: String): List<HttpsRrRecord>
}

class HttpsRrResolver(
    private val query: HttpsRrQuery = DohWireHttpsRrQuery(),
    private val fallbackQuery: HttpsRrQuery? = AndroidSystemHttpsRrQuery(),
) {
    suspend fun fetch(host: String): HttpsRrRecord? {
        val primary = query.fetchEchRecordOrNull(host)
        if (primary.record != null || primary.terminal) return primary.record
        return fallbackQuery?.fetchEchRecordOrNull(host)?.record
    }

    private fun List<HttpsRrRecord>.selectEchRecord(): HttpsRrRecord? =
        filter { record -> !record.echConfig.isNullOrEmptyBytes() }.minByOrNull { record -> record.priority }

    private suspend fun HttpsRrQuery.fetchEchRecordOrNull(host: String): LookupResult =
        try {
            followAliases(host)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            LookupResult()
        } catch (_: IllegalArgumentException) {
            LookupResult()
        } catch (_: IllegalStateException) {
            LookupResult()
        }

    private suspend fun HttpsRrQuery.followAliases(host: String): LookupResult {
        var name = host.trimEnd('.').lowercase(Locale.ROOT)
        val visited = mutableSetOf<String>()
        repeat(MaxAliasHops + 1) {
            if (!visited.add(name)) return LookupResult(terminal = true)
            val records = query(name)
            val alias = records.firstOrNull { record -> record.priority == 0 }
            if (alias == null) return LookupResult(record = records.selectEchRecord())
            name = alias.targetName.trimEnd('.').lowercase(Locale.ROOT)
            if (name.isEmpty()) return LookupResult(terminal = true)
        }
        return LookupResult(terminal = true)
    }

    private data class LookupResult(
        val record: HttpsRrRecord? = null,
        val terminal: Boolean = false,
    )

    private companion object {
        private const val MaxAliasHops = 3
    }
}

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
    override suspend fun query(host: String): List<HttpsRrRecord> =
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
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            val records =
                                response.use {
                                    if (!it.isSuccessful) throw IOException("DoH HTTPS RR query failed: ${it.code}")
                                    HttpsDnsWireParser.parseResponse(it.body.bytes(), query)
                                }
                            continuation.resume(records)
                        } catch (error: IOException) {
                            continuation.resumeWithException(error)
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
    override suspend fun query(host: String): List<HttpsRrRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        // TODO(author): DnsResolver.getInstance() deprecated; needs Context plumbing
        @Suppress("DEPRECATION")
        val resolver = resolverProvider?.invoke() ?: DnsResolver.getInstance()
        val bytes =
            suspendCancellableCoroutine { continuation ->
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
                            if (rcode == 0) {
                                continuation.resume(answer)
                            } else {
                                continuation.resume(ByteArray(0))
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            continuation.resumeWithException(error)
                        }
                    },
                )
            }
        return HttpsDnsWireParser.parseSystemResponse(bytes, host)
    }
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
) {
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
        val record =
            try {
                resolver.fetch(target)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        val echConfig = record?.echConfig
        if (echConfig.isNullOrEmptyBytes()) {
            return EchProbeResult(
                target = target,
                verdict = EchProbeVerdict.ECH_NO_CONFIG,
                httpsRrFetched = false,
                echConfigBytesB64 = null,
                tlsLatencyMs = null,
                negotiatedEch = false,
                errorDetail = null,
                vanillaTlsOk = vanillaTlsOk,
            )
        }
        val configBytes = requireNotNull(echConfig)

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
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, error)
        } catch (error: IllegalArgumentException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, error)
        } catch (error: IllegalStateException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, error)
        } catch (error: UnsupportedOperationException) {
            buildFailedEchProbeResult(target, configBytes, vanillaTlsOk, error)
        }
    }

    private fun buildFailedEchProbeResult(
        target: String,
        configBytes: ByteArray,
        vanillaTlsOk: Boolean?,
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
