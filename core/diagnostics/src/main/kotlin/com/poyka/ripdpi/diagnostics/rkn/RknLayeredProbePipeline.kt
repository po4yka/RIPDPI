package com.poyka.ripdpi.diagnostics.rkn

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
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.naming.ldap.LdapName
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
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
    private val tlsProbe: RknTlsProbe = SslSocketRknTlsProbe(),
    private val httpProbe: RknHttpProbe = OkHttpRknHttpProbe(),
    private val stubPageDetector: RknStubPageDetector = RknStubPageDetector(DefaultStubMarkers),
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
            )

        val dns =
            runCatching { dnsProbe.compare(host) }
                .getOrElse { error ->
                    return base
                        .copy(
                            verdict = RknVerdict.DOWN,
                            confidence = RknConfidence.LOW,
                            dnsError = error.message,
                        ).build()
                }
        val afterDns = base.withDns(dns)
        when (dns.verdict) {
            DnsComparisonVerdict.DNS_BLOCK -> {
                return afterDns
                    .copy(
                        verdict = RknVerdict.DNS_BLOCK,
                        confidence = RknConfidence.HIGH,
                    ).build()
            }

            DnsComparisonVerdict.DOWN -> {
                return afterDns
                    .copy(
                        verdict = RknVerdict.DOWN,
                        confidence = RknConfidence.LOW,
                    ).build()
            }

            DnsComparisonVerdict.DNS_REWRITE,
            DnsComparisonVerdict.OK,
            -> {
                Unit
            }
        }

        val tcp =
            runCatching { tcpProbe.connect(host, HttpsPort, timeoutMs) }
                .getOrElse { error ->
                    return afterDns.withTcpError(error).build()
                }
        val afterTcp = afterDns.withTcp(tcp)

        val tls =
            runCatching { tlsProbe.handshake(host, HttpsPort, timeoutMs) }
                .getOrElse { error ->
                    return afterTcp.withTlsError(error).build()
                }
        val afterTls = afterTcp.withTls(tls)

        val http =
            runCatching {
                httpProbe.get(
                    url = target.url,
                    headers = RknProbeHeaders.build(identify = identifyProbeHeaders),
                    timeoutMs = timeoutMs,
                )
            }.getOrElse { error ->
                return afterTls.withHttpError(error).build()
            }

        val stub = stubPageDetector.detect(body = http.bodyPreview, statusCode = http.statusCode)
        return if (stub.isStub) {
            afterTls
                .copy(
                    verdict = RknVerdict.HTTP_STUB,
                    confidence = RknConfidence.HIGH,
                    statusCode = http.statusCode,
                    pltMs = http.timeMs,
                    notes =
                        afterTls.notes +
                            listOfNotNull(
                                "HTTP stub page detected".takeIf { !stub.via451 },
                                "HTTP 451 unavailable for legal reasons".takeIf { stub.via451 },
                                stub.matchedMarker?.let { "Matched stub marker: $it" },
                            ),
                ).build()
        } else {
            afterTls
                .copy(
                    verdict = RknVerdict.OK,
                    confidence = if (afterTls.dnsMismatch) RknConfidence.MEDIUM else RknConfidence.HIGH,
                    statusCode = http.statusCode,
                    pltMs = http.timeMs,
                ).build()
        }
    }

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

class SslSocketRknTlsProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: javax.net.ssl.SSLSocketFactory = SSLContext.getDefault().socketFactory,
) : RknTlsProbe {
    override suspend fun handshake(
        host: String,
        port: Int,
        timeoutMs: Long,
    ): RknTlsProbeResult =
        withContext(dispatcher) {
            lateinit var socket: SSLSocket
            val elapsedMs =
                measureTimeMillis {
                    socket = socketFactory.createSocket(host, port) as SSLSocket
                    socket.use { sslSocket ->
                        sslSocket.soTimeout = timeoutMs.toInt()
                        sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                        sslSocket.sslParameters =
                            sslSocket.sslParameters.apply {
                                serverNames = listOf(SNIHostName(host))
                            }
                        sslSocket.startHandshake()
                    }
                }
            val certCn =
                socket.session
                    .peerCertificates
                    .firstOrNull()
                    ?.let { certificate -> (certificate as? X509Certificate)?.subjectCn() }
            RknTlsProbeResult(ok = true, timeMs = elapsedMs, certCn = certCn)
        }

    private fun X509Certificate.subjectCn(): String? =
        runCatching {
            LdapName(subjectX500Principal.name)
                .rdns
                .firstOrNull { rdn -> rdn.type.equals("CN", ignoreCase = true) }
                ?.value
                ?.toString()
        }.getOrNull()
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

private fun String?.containsReset(): Boolean =
    this
        ?.lowercase(Locale.ROOT)
        ?.contains("reset") == true
