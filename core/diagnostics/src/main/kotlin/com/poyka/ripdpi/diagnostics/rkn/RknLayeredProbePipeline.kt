package com.poyka.ripdpi.diagnostics.rkn

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

enum class RknVerdict {
    OK,
    DNS_BLOCK,
    TCP_RESET,
    TLS_BLOCK,
    HTTP_STUB,
    TIMEOUT,
    DOWN,
    UNKNOWN,
}

enum class RknConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class RknProbeTarget(
    val name: String,
    val url: String,
)

data class RknCheckResult(
    val name: String,
    val url: String,
    val verdict: RknVerdict,
    val confidence: RknConfidence,
    val notes: List<String> = emptyList(),
    val sysIps: Set<String> = emptySet(),
    val dohIps: Set<String> = emptySet(),
    val sysIp: String? = null,
    val dohIp: String? = null,
    val dnsMismatch: Boolean = false,
    val dnsError: String? = null,
    val tcpOk: Boolean = false,
    val tcpTimeMs: Long? = null,
    val tcpError: String? = null,
    val tlsOk: Boolean = false,
    val tlsTimeMs: Long? = null,
    val tlsCertCn: String? = null,
    val tlsError: String? = null,
    val statusCode: Int? = null,
    val pltMs: Long? = null,
    val httpError: String? = null,
    val tlsClientState: DiagnosticsTlsClientState? = null,
)

data class RknTcpProbeResult(
    val ok: Boolean,
    val timeMs: Long,
)

data class RknTlsProbeResult(
    val ok: Boolean,
    val timeMs: Long,
    val certCn: String?,
)

data class RknHttpProbeResult(
    val statusCode: Int,
    val bodyPreview: String,
    val timeMs: Long = 0,
)

fun interface RknDnsProbe {
    suspend fun compare(host: String): DnsComparisonResult
}

fun interface RknTcpProbe {
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long,
    ): RknTcpProbeResult
}

fun interface RknTlsProbe {
    suspend fun handshake(
        host: String,
        port: Int,
        timeoutMs: Long,
    ): RknTlsProbeResult
}

fun interface RknHttpProbe {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): RknHttpProbeResult
}

class RknLayeredProbePipeline(
    private val dnsProbe: RknDnsProbe = RknSystemDohDnsProbe(),
    private val tcpProbe: RknTcpProbe = SocketRknTcpProbe(),
    private val tlsProbe: RknTlsProbe = HttpClientRknTlsProbe(),
    private val httpProbe: RknHttpProbe = OkHttpRknHttpProbe(),
    private val stubPageDetector: RknStubPageDetector = RknStubPageDetector(DefaultStubMarkers),
    private val tlsClientStateProvider: () -> DiagnosticsTlsClientState? = { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = DefaultTimeoutMs,
    private val identifyProbeHeaders: Boolean = false,
) {
    suspend fun checkUrl(target: RknProbeTarget): RknCheckResult {
        val host = target.url.hostOrFallback()
        val base =
            RknResultBuilder(
                name = target.name,
                url = target.url,
                tlsClientState = tlsClientStateProvider(),
            )
        val dnsStage = runDnsStage(host, base)
        return dnsStage.result ?: checkReachability(target = target, host = host, afterDns = dnsStage.builder)
    }

    private suspend fun runDnsStage(
        host: String,
        base: RknResultBuilder,
    ): RknStageResult {
        val dns = runCatching { dnsProbe.compare(host) }
        val result =
            dns.fold(
                onSuccess = { comparison -> comparison.toTerminalDnsResult(base) },
                onFailure = { error -> base.copy(verdict = RknVerdict.DOWN, dnsError = error.message).build() },
            )
        return RknStageResult(
            builder = dns.getOrNull()?.let(base::withDns) ?: base,
            result = result,
        )
    }

    private fun DnsComparisonResult.toTerminalDnsResult(base: RknResultBuilder): RknCheckResult? {
        val afterDns = base.withDns(this)
        return when (verdict) {
            DnsComparisonVerdict.DNS_BLOCK -> {
                afterDns.copy(verdict = RknVerdict.DNS_BLOCK, confidence = RknConfidence.HIGH).build()
            }

            DnsComparisonVerdict.DOWN -> {
                afterDns.copy(verdict = RknVerdict.DOWN, confidence = RknConfidence.LOW).build()
            }

            DnsComparisonVerdict.DNS_REWRITE,
            DnsComparisonVerdict.OK,
            -> {
                null
            }
        }
    }

    private suspend fun checkReachability(
        target: RknProbeTarget,
        host: String,
        afterDns: RknResultBuilder,
    ): RknCheckResult {
        val tcp = runCatching { tcpProbe.connect(host, HttpsPort, timeoutMs) }
        val afterTcp = tcp.getOrNull()?.let(afterDns::withTcp)
        val tls = afterTcp?.let { runCatching { tlsProbe.handshake(host, HttpsPort, timeoutMs) } }
        val afterTls = tls?.getOrNull()?.let { result -> afterTcp.withTls(result) }
        val http = afterTls?.let { runHttpProbe(target) }
        return when {
            tcp.isFailure -> afterDns.withTcpError(tcp.requireFailure()).build()
            tls?.isFailure == true -> afterTcp.withTlsError(tls.requireFailure()).build()
            http?.isFailure == true -> afterTls.withHttpError(http.requireFailure()).build()
            else -> buildHttpResult(requireNotNull(afterTls), requireNotNull(http?.getOrNull()))
        }
    }

    private suspend fun runHttpProbe(target: RknProbeTarget): Result<RknHttpProbeResult> =
        runCatching {
            httpProbe.get(
                url = target.url,
                headers = RknProbeHeaders.build(identify = identifyProbeHeaders),
                timeoutMs = timeoutMs,
            )
        }

    private fun buildHttpResult(
        afterTls: RknResultBuilder,
        http: RknHttpProbeResult,
    ): RknCheckResult {
        val stub = stubPageDetector.detect(body = http.bodyPreview, statusCode = http.statusCode)
        return afterTls
            .copy(
                verdict = if (stub.isStub) RknVerdict.HTTP_STUB else RknVerdict.OK,
                confidence = httpConfidence(afterTls, stub),
                statusCode = http.statusCode,
                pltMs = http.timeMs,
                notes = afterTls.notes + stub.notes(),
            ).build()
    }

    private fun httpConfidence(
        afterTls: RknResultBuilder,
        stub: RknStubDetection,
    ): RknConfidence =
        when {
            stub.isStub -> RknConfidence.HIGH
            afterTls.dnsMismatch -> RknConfidence.MEDIUM
            else -> RknConfidence.HIGH
        }

    private fun RknStubDetection.notes(): List<String> =
        if (isStub) {
            listOfNotNull(
                "HTTP stub page detected".takeIf { !via451 },
                "HTTP 451 unavailable for legal reasons".takeIf { via451 },
                matchedMarker?.let { "Matched stub marker: $it" },
            )
        } else {
            emptyList()
        }

    private fun <T> Result<T>.requireFailure(): Throwable = requireNotNull(exceptionOrNull())

    fun iterCheckUrls(
        targets: List<RknProbeTarget>,
        workers: Int = DefaultWorkers,
    ): Flow<RknCheckResult> =
        channelFlow {
            val semaphore = Semaphore(workers.coerceAtLeast(1))
            targets.forEach { target ->
                launch(dispatcher) {
                    semaphore.withPermit {
                        send(checkUrl(target))
                    }
                }
            }
        }

    private data class RknStageResult(
        val builder: RknResultBuilder,
        val result: RknCheckResult?,
    )

    private data class RknResultBuilder(
        val name: String,
        val url: String,
        val verdict: RknVerdict = RknVerdict.UNKNOWN,
        val confidence: RknConfidence = RknConfidence.LOW,
        val notes: List<String> = emptyList(),
        val sysIps: Set<String> = emptySet(),
        val dohIps: Set<String> = emptySet(),
        val sysIp: String? = null,
        val dohIp: String? = null,
        val dnsMismatch: Boolean = false,
        val dnsError: String? = null,
        val tcpOk: Boolean = false,
        val tcpTimeMs: Long? = null,
        val tcpError: String? = null,
        val tlsOk: Boolean = false,
        val tlsTimeMs: Long? = null,
        val tlsCertCn: String? = null,
        val tlsError: String? = null,
        val statusCode: Int? = null,
        val pltMs: Long? = null,
        val httpError: String? = null,
        val tlsClientState: DiagnosticsTlsClientState? = null,
    ) {
        fun withDns(result: DnsComparisonResult): RknResultBuilder =
            copy(
                notes = notes + result.notes,
                sysIps = result.sysIps,
                dohIps = result.dohIps,
                sysIp = result.sysIp,
                dohIp = result.dohIp,
                dnsMismatch = result.verdict == DnsComparisonVerdict.DNS_REWRITE,
            )

        fun withTcp(result: RknTcpProbeResult): RknResultBuilder =
            copy(
                tcpOk = result.ok,
                tcpTimeMs = result.timeMs,
            )

        fun withTcpError(error: Throwable): RknResultBuilder {
            val message = error.message
            return when {
                error is SocketTimeoutException -> {
                    copy(
                        verdict = RknVerdict.TIMEOUT,
                        confidence = RknConfidence.LOW,
                        tcpError = message,
                    )
                }

                error is ConnectException && message.containsReset() -> {
                    copy(
                        verdict = RknVerdict.TCP_RESET,
                        confidence = RknConfidence.MEDIUM,
                        tcpError = message,
                    )
                }

                else -> {
                    copy(
                        verdict = RknVerdict.DOWN,
                        confidence = RknConfidence.LOW,
                        tcpError = message,
                    )
                }
            }
        }

        fun withTls(result: RknTlsProbeResult): RknResultBuilder =
            copy(
                tlsOk = result.ok,
                tlsTimeMs = result.timeMs,
                tlsCertCn = result.certCn,
            )

        fun withTlsError(error: Throwable): RknResultBuilder {
            val message = error.message
            val confidence =
                if (error is SocketTimeoutException || message.containsReset()) {
                    RknConfidence.MEDIUM
                } else {
                    RknConfidence.LOW
                }
            return copy(
                verdict = RknVerdict.TLS_BLOCK,
                confidence = confidence,
                tlsError = message,
            )
        }

        fun withHttpError(error: Throwable): RknResultBuilder =
            if (error is SocketTimeoutException) {
                copy(
                    verdict = RknVerdict.TIMEOUT,
                    confidence = RknConfidence.LOW,
                    httpError = error.message,
                )
            } else {
                copy(
                    verdict = RknVerdict.DOWN,
                    confidence = RknConfidence.LOW,
                    httpError = error.message,
                )
            }

        fun build(): RknCheckResult =
            RknCheckResult(
                name = name,
                url = url,
                verdict = verdict,
                confidence = confidence,
                notes = notes,
                sysIps = sysIps,
                dohIps = dohIps,
                sysIp = sysIp,
                dohIp = dohIp,
                dnsMismatch = dnsMismatch,
                dnsError = dnsError,
                tcpOk = tcpOk,
                tcpTimeMs = tcpTimeMs,
                tcpError = tcpError,
                tlsOk = tlsOk,
                tlsTimeMs = tlsTimeMs,
                tlsCertCn = tlsCertCn,
                tlsError = tlsError,
                statusCode = statusCode,
                pltMs = pltMs,
                httpError = httpError,
                tlsClientState = tlsClientState,
            )
    }

    private companion object {
        private const val HttpsPort = 443
        private const val DefaultTimeoutMs = 5_000L
        private const val DefaultWorkers = 10
    }
}

class RknSystemDohDnsProbe(
    private val comparator: SystemDohDnsComparator = SystemDohDnsComparator(),
) : RknDnsProbe {
    override suspend fun compare(host: String): DnsComparisonResult = comparator.compare(host)
}

class SocketRknTcpProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RknTcpProbe {
    override suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long,
    ): RknTcpProbeResult =
        withContext(dispatcher) {
            var elapsedMs = 0L
            Socket().use { socket ->
                elapsedMs =
                    measureTimeMillis {
                        socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                    }
            }
            RknTcpProbeResult(ok = true, timeMs = elapsedMs)
        }
}

class HttpClientRknTlsProbe(
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RknTlsProbe {
    override suspend fun handshake(
        host: String,
        port: Int,
        timeoutMs: Long,
    ): RknTlsProbeResult =
        withContext(dispatcher) {
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
                    .url(host.toHttpsProbeUrl(port))
                    .head()
                    .header("Host", host)
                    .build()
            val elapsedMs =
                measureTimeMillis {
                    client.newCall(request).execute().close()
                }
            RknTlsProbeResult(ok = true, timeMs = elapsedMs, certCn = null)
        }
}

class OkHttpRknHttpProbe(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RknHttpProbe {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): RknHttpProbeResult =
        withContext(dispatcher) {
            val client =
                baseClient
                    .newBuilder()
                    .followRedirects(true)
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            val requestBuilder = Request.Builder().url(url).get()
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }

            var responseCode = 0
            var bodyPreview = ""
            val elapsedMs =
                measureTimeMillis {
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        responseCode = response.code
                        bodyPreview = response.body.string().take(HttpBodyPreviewChars)
                    }
                }
            RknHttpProbeResult(statusCode = responseCode, bodyPreview = bodyPreview, timeMs = elapsedMs)
        }

    private companion object {
        private const val HttpBodyPreviewChars = 2_000
    }
}

private fun String.hostOrFallback(): String = URL(this).host

private fun String.toHttpsProbeUrl(port: Int): String =
    if (port == StandardHttpsPort) {
        "https://$this/"
    } else {
        "https://$this:$port/"
    }

private fun String?.containsReset(): Boolean =
    this
        ?.lowercase(Locale.ROOT)
        ?.contains("reset") == true

private const val StandardHttpsPort = 443
