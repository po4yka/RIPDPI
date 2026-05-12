package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.core.detection.dpi.DpiErrorClassifier
import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import com.poyka.ripdpi.core.detection.dpi.IpAddressClassifier
import com.poyka.ripdpi.core.detection.dpi.IpAddressType
import com.poyka.ripdpi.core.detection.dpi.ProbeStage
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpich.RandomHostHeaderGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

enum class DomainVerdict {
    OK,
    BLOCKED,
    TLS_VERSION_BLOCK,
    ISP_PAGE,
    TCP16_BAND,
    DNS_FAIL,
    UNREACHABLE,
    FAKE_IP,
}

enum class AttemptStatus {
    OK,
    BLOCKED,
    REDIR_OK,
    REDIR_SUSPICIOUS,
    ISP_PAGE,
    FAKE_IP,
    TCP16_BAND_TIMEOUT,
    ERROR,
}

enum class ReachabilityProbeKind {
    TLS13,
    TLS12,
    HTTP,
}

data class AttemptResult(
    val status: AttemptStatus,
    val detail: String = "",
    val bytesRead: Int = 0,
    val latencyMs: Long = 0,
    val stage: ProbeStage = ProbeStage.TCP_CONNECT,
    val error: DpiProbeError? = null,
    val statusCode: Int? = null,
)

data class DomainReachabilityResult(
    val domain: String,
    val resolvedIps: List<String>,
    val tls13: AttemptResult,
    val tls12: AttemptResult,
    val http: AttemptResult,
    val verdict: DomainVerdict,
    val tlsClientState: DiagnosticsTlsClientState? = null,
    val requestedHosts: List<String>? = null,
)

data class ReachabilityProbeEndpoint(
    val connectHost: String,
    val port: Int,
    val hostHeader: String,
)

typealias DomainAddressResolver = suspend (domain: String) -> List<String>

typealias DomainReachabilityAttemptRunner = suspend (
    domain: String,
    kind: ReachabilityProbeKind,
    stubIps: Set<String>,
    requestedHost: String?,
) -> AttemptResult

class DomainReachabilityScanner(
    private val resolver: DomainAddressResolver = SystemDomainAddressResolver()::resolveA,
    private val attemptRunner: DomainReachabilityAttemptRunner = OkHttpDomainReachabilityAttemptRunner()::invoke,
    private val tlsClientStateProvider: () -> DiagnosticsTlsClientState? = { null },
    private val maxConcurrent: Int = DefaultMaxConcurrent,
) {
    private val semaphore = Semaphore(maxConcurrent)

    fun withMaxConcurrent(maxConcurrent: Int): DomainReachabilityScanner =
        DomainReachabilityScanner(
            resolver = resolver,
            attemptRunner = attemptRunner,
            tlsClientStateProvider = tlsClientStateProvider,
            maxConcurrent = maxConcurrent,
        )

    suspend fun scan(
        domains: List<String>,
        stubIps: Set<String>,
        randomHostname: Boolean = false,
    ): List<DomainReachabilityResult> =
        coroutineScope {
            domains
                .map { domain ->
                    async {
                        semaphore.withPermit {
                            scanDomain(domain, stubIps, randomHostname = randomHostname)
                        }
                    }
                }.map { deferred -> deferred.await() }
        }

    private suspend fun scanDomain(
        domain: String,
        stubIps: Set<String>,
        randomHostname: Boolean,
    ): DomainReachabilityResult {
        val resolvedIps =
            runCatching { resolver(domain) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    return shortCircuit(domain, emptyList(), AttemptStatus.ERROR, DomainVerdict.DNS_FAIL)
                }

        if (resolvedIps.any { ip -> ip in stubIps }) {
            return shortCircuit(domain, resolvedIps, AttemptStatus.ISP_PAGE, DomainVerdict.ISP_PAGE)
        }
        if (resolvedIps.any { ip -> IpAddressClassifier.classify(ip) == IpAddressType.FAKE_IP }) {
            return shortCircuit(domain, resolvedIps, AttemptStatus.FAKE_IP, DomainVerdict.FAKE_IP)
        }

        val requestedHosts = mutableListOf<String>()
        val tls13 = runAttempt(domain, ReachabilityProbeKind.TLS13, stubIps, randomHostname, requestedHosts)
        val tls12 = runAttempt(domain, ReachabilityProbeKind.TLS12, stubIps, randomHostname, requestedHosts)
        val http = runAttempt(domain, ReachabilityProbeKind.HTTP, stubIps, randomHostname, requestedHosts)
        return DomainReachabilityResult(
            domain = domain,
            resolvedIps = resolvedIps,
            tls13 = tls13,
            tls12 = tls12,
            http = http,
            verdict = aggregateVerdict(tls13, tls12, http),
            tlsClientState = tlsClientStateProvider(),
            requestedHosts = requestedHosts.takeIf { randomHostname },
        )
    }

    private suspend fun runAttempt(
        domain: String,
        kind: ReachabilityProbeKind,
        stubIps: Set<String>,
        randomHostname: Boolean,
        requestedHosts: MutableList<String>,
    ): AttemptResult =
        try {
            val requestedHost =
                if (randomHostname) {
                    RandomHostHeaderGenerator.next().also(requestedHosts::add)
                } else {
                    null
                }
            attemptRunner(domain, kind, stubIps, requestedHost).classifyStubRedirect(stubIps)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            classifyException(error, ProbeStage.TCP_CONNECT, bytesRead = 0)
        }

    private fun AttemptResult.classifyStubRedirect(stubIps: Set<String>): AttemptResult {
        if (status != AttemptStatus.REDIR_SUSPICIOUS) {
            return this
        }
        val redirectedHost = runCatching { detail.toHttpUrl().host }.getOrNull()
        return if (redirectedHost in stubIps || stubIps.any { ip -> detail.contains(ip) }) {
            copy(status = AttemptStatus.ISP_PAGE)
        } else {
            this
        }
    }

    private fun shortCircuit(
        domain: String,
        resolvedIps: List<String>,
        status: AttemptStatus,
        verdict: DomainVerdict,
    ): DomainReachabilityResult {
        val attempt = AttemptResult(status = status, detail = verdict.name.lowercase())
        return DomainReachabilityResult(
            domain = domain,
            resolvedIps = resolvedIps,
            tls13 = attempt,
            tls12 = attempt,
            http = attempt,
            verdict = verdict,
            tlsClientState = tlsClientStateProvider(),
        )
    }

    private fun aggregateVerdict(
        tls13: AttemptResult,
        tls12: AttemptResult,
        http: AttemptResult,
    ): DomainVerdict {
        val attempts = listOf(tls13, tls12, http)
        val hasTcp16Band = attempts.any { it.status == AttemptStatus.TCP16_BAND_TIMEOUT }
        val hasIspPage = attempts.any { it.status == AttemptStatus.ISP_PAGE }
        val hasHttpBlock = http.status == AttemptStatus.BLOCKED || http.status == AttemptStatus.REDIR_SUSPICIOUS
        val hasTlsBlock = listOf(tls13, tls12).any { it.status == AttemptStatus.BLOCKED }
        val tls13OnlyWorks = tls13.status == AttemptStatus.OK && tls12.status == AttemptStatus.ERROR
        val tls12OnlyWorks = tls12.status == AttemptStatus.OK && tls13.status == AttemptStatus.ERROR

        if (hasTcp16Band) {
            return DomainVerdict.TCP16_BAND
        }
        if (hasIspPage) {
            return DomainVerdict.ISP_PAGE
        }
        if (hasHttpBlock || hasTlsBlock) {
            return DomainVerdict.BLOCKED
        }
        if (tls13OnlyWorks || tls12OnlyWorks) {
            return DomainVerdict.TLS_VERSION_BLOCK
        }
        if (attempts.all { it.status == AttemptStatus.ERROR }) {
            return DomainVerdict.UNREACHABLE
        }
        return DomainVerdict.OK
    }

    companion object {
        fun classifyException(
            error: Throwable,
            stage: ProbeStage,
            bytesRead: Int,
        ): AttemptResult {
            if (error is SocketTimeoutException && bytesRead in Tcp16MinBytes..Tcp16MaxBytes) {
                return AttemptResult(
                    status = AttemptStatus.TCP16_BAND_TIMEOUT,
                    detail = "TCP16 band timeout after $bytesRead bytes",
                    bytesRead = bytesRead,
                    stage = stage,
                    error = DpiProbeError.Unknown,
                )
            }
            return AttemptResult(
                status = AttemptStatus.ERROR,
                detail = error.message.orEmpty(),
                bytesRead = bytesRead,
                stage = stage,
                error = DpiErrorClassifier.classify(error, stage),
            )
        }
    }
}

class SystemDomainAddressResolver(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resolveA(domain: String): List<String> =
        withContext(dispatcher) {
            InetAddress
                .getAllByName(domain)
                .asSequence()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { address -> address.hostAddress }
                .toList()
        }
}

class OkHttpDomainReachabilityAttemptRunner(
    private val timeoutMs: Long = DefaultAttemptTimeoutMs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val endpointResolver: ReachabilityProbeEndpointResolver = DefaultReachabilityProbeEndpointResolver,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) {
    suspend operator fun invoke(
        domain: String,
        kind: ReachabilityProbeKind,
        stubIps: Set<String>,
        requestedHost: String? = null,
    ): AttemptResult =
        withContext(dispatcher) {
            when (kind) {
                ReachabilityProbeKind.HTTP -> runHttpHead(domain, stubIps, requestedHost)

                ReachabilityProbeKind.TLS13,
                ReachabilityProbeKind.TLS12,
                -> runTls(domain, kind, stubIps, requestedHost)
            }
        }

    private fun runTls(
        domain: String,
        kind: ReachabilityProbeKind,
        stubIps: Set<String>,
        requestedHost: String?,
    ): AttemptResult {
        val stage = AtomicReference(ProbeStage.TCP_CONNECT)
        val endpoint = endpointResolver.endpointFor(domain, kind).withHostHeader(requestedHost)
        val client = clientFor(kind, stage)
        val request = tlsRequest(endpoint)
        var bytesRead = 0
        val elapsed =
            measureTimeMillis {
                try {
                    client.newCall(request).execute().use { response ->
                        stage.set(ProbeStage.READING_DATA)
                        bytesRead = response.body.bytes().size
                        return response.toAttemptResult(domain, kind, bytesRead, 0, stubIps)
                    }
                } catch (error: Exception) {
                    return DomainReachabilityScanner.classifyException(
                        error = error,
                        stage = stage.get(),
                        bytesRead = bytesRead,
                    )
                }
            }
        return AttemptResult(status = AttemptStatus.ERROR, latencyMs = elapsed)
    }

    private fun tlsRequest(endpoint: ReachabilityProbeEndpoint): Request =
        Request
            .Builder()
            .url("https://${endpoint.connectHost}:${endpoint.port}/")
            .method("GET", null)
            .header("Host", endpoint.hostHeader)
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .build()

    private fun runHttpHead(
        domain: String,
        stubIps: Set<String>,
        requestedHost: String?,
    ): AttemptResult {
        var stage = ProbeStage.TCP_CONNECT
        var bytesRead = 0
        val startedAt = System.nanoTime()
        val endpoint = endpointResolver.endpointFor(domain, ReachabilityProbeKind.HTTP).withHostHeader(requestedHost)
        val response = ByteArrayOutputStream()
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                socket.connect(InetSocketAddress(endpoint.connectHost, endpoint.port), timeoutMs.toInt())
                stage = ProbeStage.SENDING_DATA
                socket.getOutputStream().write(httpHeadRequest(endpoint.hostHeader))
                socket.getOutputStream().flush()
                stage = ProbeStage.READING_DATA
                val buffer = ByteArray(HttpReadBufferSize)
                while (true) {
                    val read = socket.getInputStream().read(buffer)
                    if (read == -1) break
                    response.write(buffer, 0, read)
                    bytesRead += read
                    if (bytesRead >= HttpResponseReadLimit) break
                }
                parsePartialHttpResponse(
                    domain = domain,
                    response = response,
                    bytesRead = bytesRead,
                    stubIps = stubIps,
                ).copy(latencyMs = elapsedMillis(startedAt), stage = ProbeStage.READING_DATA)
            }
        } catch (error: Exception) {
            val hasPartialNonTcp16Response =
                stage == ProbeStage.READING_DATA &&
                    response.size() > 0 &&
                    bytesRead !in Tcp16MinBytes..Tcp16MaxBytes
            if (hasPartialNonTcp16Response) {
                return parsePartialHttpResponse(
                    domain = domain,
                    response = response,
                    bytesRead = bytesRead,
                    stubIps = stubIps,
                ).copy(latencyMs = elapsedMillis(startedAt), stage = ProbeStage.READING_DATA)
            }
            DomainReachabilityScanner
                .classifyException(error, stage, bytesRead)
                .copy(latencyMs = elapsedMillis(startedAt))
        }
    }

    private fun ReachabilityProbeEndpoint.withHostHeader(requestedHost: String?): ReachabilityProbeEndpoint =
        if (requestedHost == null) {
            this
        } else {
            copy(hostHeader = requestedHost)
        }

    private fun httpHeadRequest(domain: String): ByteArray =
        "HEAD / HTTP/1.1\r\nHost: $domain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)

    private fun parseHttpResponse(
        domain: String,
        response: String,
        bytesRead: Int,
        stubIps: Set<String>,
    ): AttemptResult {
        val lines = response.lineSequence().toList()
        val statusCode =
            lines
                .firstOrNull()
                ?.split(' ')
                ?.getOrNull(1)
                ?.toIntOrNull()
        val location =
            lines.firstNotNullOfOrNull { line ->
                line
                    .substringAfter("Location:", missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        return when {
            statusCode == 451 -> {
                AttemptResult(AttemptStatus.BLOCKED, statusCode = statusCode, bytesRead = bytesRead)
            }

            statusCode != null && statusCode in 300..399 && location != null -> {
                redirectAttempt(domain, location, statusCode, bytesRead, 0, stubIps)
            }

            statusCode != null && statusCode in 200..499 -> {
                AttemptResult(AttemptStatus.OK, statusCode = statusCode, bytesRead = bytesRead)
            }

            else -> {
                AttemptResult(
                    status = AttemptStatus.ERROR,
                    detail = response.lineSequence().firstOrNull().orEmpty(),
                    bytesRead = bytesRead,
                )
            }
        }
    }

    private fun parsePartialHttpResponse(
        domain: String,
        response: ByteArrayOutputStream,
        bytesRead: Int,
        stubIps: Set<String>,
    ): AttemptResult =
        parseHttpResponse(
            domain = domain,
            response = String(response.toByteArray(), StandardCharsets.ISO_8859_1),
            bytesRead = bytesRead,
            stubIps = stubIps,
        )

    private fun clientFor(
        kind: ReachabilityProbeKind,
        stage: AtomicReference<ProbeStage>,
    ): OkHttpClient =
        clientBuilder {
            eventListenerFactory(ProbeEventListenerFactory(stage))
            addNetworkInterceptor(StageTrackingInterceptor(stage))
            followRedirects(false)
            followSslRedirects(false)
            connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            when (kind) {
                ReachabilityProbeKind.TLS13 -> withPinnedTls(TlsVersion.TLS_1_3)
                ReachabilityProbeKind.TLS12 -> withPinnedTls(TlsVersion.TLS_1_2)
                ReachabilityProbeKind.HTTP -> Unit
            }
        }

    private fun okhttp3.Response.toAttemptResult(
        domain: String,
        kind: ReachabilityProbeKind,
        bytesRead: Int,
        latencyMs: Long,
        stubIps: Set<String>,
    ): AttemptResult {
        val location = header("Location")
        return when {
            code == 451 -> {
                AttemptResult(AttemptStatus.BLOCKED, statusCode = code, bytesRead = bytesRead, latencyMs = latencyMs)
            }

            code in 300..399 && location != null -> {
                redirectAttempt(domain, location, code, bytesRead, latencyMs, stubIps)
            }

            code in 200..499 -> {
                AttemptResult(AttemptStatus.OK, statusCode = code, bytesRead = bytesRead, latencyMs = latencyMs)
            }

            else -> {
                AttemptResult(
                    status = AttemptStatus.ERROR,
                    detail = "${kind.name} HTTP $code",
                    statusCode = code,
                    bytesRead = bytesRead,
                    latencyMs = latencyMs,
                )
            }
        }
    }

    private fun redirectAttempt(
        domain: String,
        location: String,
        code: Int,
        bytesRead: Int,
        latencyMs: Long,
        stubIps: Set<String>,
    ): AttemptResult {
        val redirectedHost = runCatching { location.toHttpUrl().host }.getOrNull()
        val sameDomain = redirectedHost == domain || redirectedHost?.endsWith(".$domain") == true
        if (redirectedHost in stubIps) {
            return AttemptResult(
                status = AttemptStatus.ISP_PAGE,
                detail = location,
                statusCode = code,
                bytesRead = bytesRead,
                latencyMs = latencyMs,
                stage = ProbeStage.READING_DATA,
            )
        }
        return AttemptResult(
            status = if (sameDomain) AttemptStatus.REDIR_OK else AttemptStatus.REDIR_SUSPICIOUS,
            detail = location,
            statusCode = code,
            bytesRead = bytesRead,
            latencyMs = latencyMs,
        )
    }
}

fun interface ReachabilityProbeEndpointResolver {
    fun endpointFor(
        domain: String,
        kind: ReachabilityProbeKind,
    ): ReachabilityProbeEndpoint
}

private object DefaultReachabilityProbeEndpointResolver : ReachabilityProbeEndpointResolver {
    override fun endpointFor(
        domain: String,
        kind: ReachabilityProbeKind,
    ): ReachabilityProbeEndpoint =
        ReachabilityProbeEndpoint(
            connectHost = domain,
            port =
                when (kind) {
                    ReachabilityProbeKind.HTTP -> HttpPort

                    ReachabilityProbeKind.TLS13,
                    ReachabilityProbeKind.TLS12,
                    -> HttpsPort
                },
            hostHeader = domain,
        )
}

object TlsVersionPinner {
    fun tls13Spec(): ConnectionSpec = tlsSpec(TlsVersion.TLS_1_3)

    fun tls12Spec(): ConnectionSpec = tlsSpec(TlsVersion.TLS_1_2)

    fun tlsSpec(version: TlsVersion): ConnectionSpec =
        ConnectionSpec
            .Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(version)
            .build()
}

private fun OkHttpClient.Builder.withPinnedTls(version: TlsVersion): OkHttpClient.Builder =
    connectionSpecs(listOf(TlsVersionPinner.tlsSpec(version)))

private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

private class ProbeEventListenerFactory(
    private val stage: AtomicReference<ProbeStage>,
) : EventListener.Factory {
    override fun create(call: okhttp3.Call): EventListener = OkHttpProbeEventListener(stage)
}

private class OkHttpProbeEventListener(
    private val stage: AtomicReference<ProbeStage>,
) : EventListener() {
    override fun connectStart(
        call: okhttp3.Call,
        inetSocketAddress: java.net.InetSocketAddress,
        proxy: java.net.Proxy,
    ) {
        stage.set(ProbeStage.TCP_CONNECT)
    }

    override fun secureConnectStart(call: okhttp3.Call) {
        stage.set(ProbeStage.TLS_HANDSHAKE)
    }

    override fun requestHeadersStart(call: okhttp3.Call) {
        stage.set(ProbeStage.SENDING_DATA)
    }

    override fun responseHeadersStart(call: okhttp3.Call) {
        stage.set(ProbeStage.READING_DATA)
    }
}

private class StageTrackingInterceptor(
    private val stage: AtomicReference<ProbeStage>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        stage.set(ProbeStage.SENDING_DATA)
        val response = chain.proceed(chain.request())
        stage.set(ProbeStage.READING_DATA)
        return response
    }
}

private const val DefaultMaxConcurrent = 8
private const val DefaultAttemptTimeoutMs = 5_000L
private const val HttpPort = 80
private const val HttpsPort = 443
private const val HttpReadBufferSize = 2_048
private const val HttpResponseReadLimit = 64 * 1_024
private const val Tcp16MinBytes = 16_384
private const val Tcp16MaxBytes = 20_480
