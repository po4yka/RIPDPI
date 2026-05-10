package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
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

enum class ProbeStage {
    TCP_CONNECT,
    TLS_HANDSHAKE,
    SENDING_DATA,
    READING_DATA,
}

enum class ReachabilityProbeError {
    DNS_FAIL,
    TIMEOUT,
    TLS_RST,
    TCP_RST,
    REFUSED,
    UNKNOWN,
}

data class AttemptResult(
    val status: AttemptStatus,
    val detail: String = "",
    val bytesRead: Int = 0,
    val latencyMs: Long = 0,
    val stage: ProbeStage = ProbeStage.TCP_CONNECT,
    val error: ReachabilityProbeError? = null,
    val statusCode: Int? = null,
)

data class DomainReachabilityResult(
    val domain: String,
    val resolvedIps: List<String>,
    val tls13: AttemptResult,
    val tls12: AttemptResult,
    val http: AttemptResult,
    val verdict: DomainVerdict,
)

fun interface DomainAddressResolver {
    suspend fun resolveA(domain: String): List<String>
}

fun interface DomainReachabilityAttemptRunner {
    suspend fun run(
        domain: String,
        kind: ReachabilityProbeKind,
    ): AttemptResult
}

class DomainReachabilityScanner(
    private val resolver: DomainAddressResolver = SystemDomainAddressResolver(),
    private val attemptRunner: DomainReachabilityAttemptRunner = OkHttpDomainReachabilityAttemptRunner(),
    maxConcurrent: Int = DefaultMaxConcurrent,
) {
    private val semaphore = Semaphore(maxConcurrent)

    suspend fun scan(
        domains: List<String>,
        stubIps: Set<String>,
    ): List<DomainReachabilityResult> =
        coroutineScope {
            domains
                .map { domain ->
                    async {
                        semaphore.withPermit {
                            scanDomain(domain, stubIps)
                        }
                    }
                }.map { deferred -> deferred.await() }
        }

    private suspend fun scanDomain(
        domain: String,
        stubIps: Set<String>,
    ): DomainReachabilityResult {
        val resolvedIps =
            runCatching { resolver.resolveA(domain) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    return shortCircuit(domain, emptyList(), AttemptStatus.ERROR, DomainVerdict.DNS_FAIL)
                }

        if (resolvedIps.any { ip -> ip in stubIps }) {
            return shortCircuit(domain, resolvedIps, AttemptStatus.ISP_PAGE, DomainVerdict.ISP_PAGE)
        }
        if (resolvedIps.any(::isFakeIp)) {
            return shortCircuit(domain, resolvedIps, AttemptStatus.FAKE_IP, DomainVerdict.FAKE_IP)
        }

        val tls13 = runAttempt(domain, ReachabilityProbeKind.TLS13)
        val tls12 = runAttempt(domain, ReachabilityProbeKind.TLS12)
        val http = runAttempt(domain, ReachabilityProbeKind.HTTP)
        return DomainReachabilityResult(
            domain = domain,
            resolvedIps = resolvedIps,
            tls13 = tls13,
            tls12 = tls12,
            http = http,
            verdict = aggregateVerdict(tls13, tls12, http),
        )
    }

    private suspend fun runAttempt(
        domain: String,
        kind: ReachabilityProbeKind,
    ): AttemptResult =
        try {
            attemptRunner.run(domain, kind)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            classifyException(error, ProbeStage.TCP_CONNECT, bytesRead = 0)
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
        )
    }

    private fun aggregateVerdict(
        tls13: AttemptResult,
        tls12: AttemptResult,
        http: AttemptResult,
    ): DomainVerdict {
        val attempts = listOf(tls13, tls12, http)
        val hasTcp16Band = attempts.any { it.status == AttemptStatus.TCP16_BAND_TIMEOUT }
        val hasHttpBlock = http.status == AttemptStatus.BLOCKED || http.status == AttemptStatus.REDIR_SUSPICIOUS
        val hasTlsBlock = listOf(tls13, tls12).any { it.status == AttemptStatus.BLOCKED }
        val tls13OnlyWorks = tls13.status == AttemptStatus.OK && tls12.status == AttemptStatus.ERROR
        val tls12OnlyWorks = tls12.status == AttemptStatus.OK && tls13.status == AttemptStatus.ERROR

        if (hasTcp16Band) {
            return DomainVerdict.TCP16_BAND
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
                    error = ReachabilityProbeError.TIMEOUT,
                )
            }
            return AttemptResult(
                status = AttemptStatus.ERROR,
                detail = error.message.orEmpty(),
                bytesRead = bytesRead,
                stage = stage,
                error = classifyError(error),
            )
        }

        private fun classifyError(error: Throwable): ReachabilityProbeError {
            val text = "${error::class.java.simpleName}: ${error.message.orEmpty()}".lowercase()
            return when {
                "unknownhost" in text || "dns" in text -> ReachabilityProbeError.DNS_FAIL
                "timeout" in text || "timed out" in text -> ReachabilityProbeError.TIMEOUT
                "reset" in text && "ssl" in text -> ReachabilityProbeError.TLS_RST
                "reset" in text -> ReachabilityProbeError.TCP_RST
                "refused" in text -> ReachabilityProbeError.REFUSED
                else -> ReachabilityProbeError.UNKNOWN
            }
        }
    }
}

class SystemDomainAddressResolver(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DomainAddressResolver {
    override suspend fun resolveA(domain: String): List<String> =
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
) : DomainReachabilityAttemptRunner {
    override suspend fun run(
        domain: String,
        kind: ReachabilityProbeKind,
    ): AttemptResult =
        withContext(dispatcher) {
            val client = clientFor(kind)
            val url = if (kind == ReachabilityProbeKind.HTTP) "http://$domain/" else "https://$domain/"
            val request =
                Request
                    .Builder()
                    .url(url)
                    .method(if (kind == ReachabilityProbeKind.HTTP) "HEAD" else "GET", null)
                    .header("Host", domain)
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "close")
                    .build()
            var bytesRead = 0
            val elapsed =
                measureTimeMillis {
                    try {
                        client.newCall(request).execute().use { response ->
                            bytesRead = response.body.bytes().size
                            return@withContext response.toAttemptResult(domain, kind, bytesRead, 0)
                        }
                    } catch (error: Exception) {
                        return@withContext DomainReachabilityScanner.classifyException(
                            error = error,
                            stage = ProbeStage.READING_DATA,
                            bytesRead = bytesRead,
                        )
                    }
                }
            AttemptResult(status = AttemptStatus.ERROR, latencyMs = elapsed)
        }

    private fun clientFor(kind: ReachabilityProbeKind): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        return when (kind) {
            ReachabilityProbeKind.TLS13 -> builder.connectionSpecs(listOf(TlsVersionPinner.tls13Spec())).build()
            ReachabilityProbeKind.TLS12 -> builder.connectionSpecs(listOf(TlsVersionPinner.tls12Spec())).build()
            ReachabilityProbeKind.HTTP -> builder.build()
        }
    }

    private fun okhttp3.Response.toAttemptResult(
        domain: String,
        kind: ReachabilityProbeKind,
        bytesRead: Int,
        latencyMs: Long,
    ): AttemptResult {
        val location = header("Location")
        return when {
            code == 451 -> {
                AttemptResult(AttemptStatus.BLOCKED, statusCode = code, bytesRead = bytesRead, latencyMs = latencyMs)
            }

            code in 300..399 && location != null -> {
                redirectAttempt(domain, location, code, bytesRead, latencyMs)
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
    ): AttemptResult {
        val redirectedHost = runCatching { location.toHttpUrl().host }.getOrNull()
        val sameDomain = redirectedHost == domain || redirectedHost?.endsWith(".$domain") == true
        return AttemptResult(
            status = if (sameDomain) AttemptStatus.REDIR_OK else AttemptStatus.REDIR_SUSPICIOUS,
            detail = location,
            statusCode = code,
            bytesRead = bytesRead,
            latencyMs = latencyMs,
        )
    }
}

object TlsVersionPinner {
    fun tls13Spec(): ConnectionSpec = tlsSpec(TlsVersion.TLS_1_3)

    fun tls12Spec(): ConnectionSpec = tlsSpec(TlsVersion.TLS_1_2)

    fun contextFor(version: TlsVersion): SSLContext =
        SSLContext
            .getInstance(
                when (version) {
                    TlsVersion.TLS_1_3 -> "TLSv1.3"
                    TlsVersion.TLS_1_2 -> "TLSv1.2"
                    else -> "TLS"
                },
            ).apply { init(null, null, null) }

    private fun tlsSpec(version: TlsVersion): ConnectionSpec =
        ConnectionSpec
            .Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(version)
            .build()
}

private fun isFakeIp(ip: String): Boolean {
    val parts = ip.split('.').mapNotNull(String::toIntOrNull)
    if (parts.size != Ipv4PartCount) return false
    return parts[0] == 198 && parts[1] in 18..19
}

private const val DefaultMaxConcurrent = 8
private const val DefaultAttemptTimeoutMs = 5_000L
private const val Tcp16MinBytes = 16_384
private const val Tcp16MaxBytes = 20_480
private const val Ipv4PartCount = 4
