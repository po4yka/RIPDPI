package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToLong

class Tcp16FatHeaderProbe(
    private val paddingPool: RandomPaddingPool = RandomPaddingPool(),
    private val requestRunner: Tcp16RequestRunner = OkHttpTcp16RequestRunner(),
    private val tlsClientStateProvider: () -> DiagnosticsTlsClientState? = { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val concurrency: Int = DefaultConcurrency,
) : Tcp16Probe {
    fun withConcurrency(concurrency: Int): Tcp16FatHeaderProbe =
        Tcp16FatHeaderProbe(
            paddingPool = paddingPool,
            requestRunner = requestRunner,
            tlsClientStateProvider = tlsClientStateProvider,
            dispatcher = dispatcher,
            concurrency = concurrency,
        )

    suspend fun run(
        targets: List<Tcp16Target>,
        rttHints: Map<String, Long> = emptyMap(),
    ): List<Tcp16ProbeResult> =
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            targets
                .map { target ->
                    async {
                        semaphore.withPermit {
                            runWithRttHint(target, rttHints[target.id])
                        }
                    }
                }.awaitAll()
        }

    suspend fun runWithRttHint(target: Tcp16Target): Tcp16ProbeResult = runWithRttHint(target, hintRttMs = null)

    override suspend fun runWithRttHint(
        target: Tcp16Target,
        hintRttMs: Long?,
    ): Tcp16ProbeResult =
        withContext(dispatcher) {
            val tracker = Tcp16ConnectionTracker()
            val rttSamples = mutableListOf<Long>()
            for (requestIndex in 0 until RequestCount) {
                val request =
                    Tcp16FatHeaderRequest(
                        requestIndex = requestIndex,
                        padding = if (requestIndex == 0) null else paddingPool.slice(PaddingBytes),
                        timeoutMs = timeoutFor(hintRttMs ?: rttSamples.averageMillis()),
                    )
                val outcome =
                    runCatching {
                        requestRunner.execute(target, request, tracker)
                    }
                if (tracker.connectionCount > 1) {
                    return@withContext target.result(
                        alive = true,
                        verdict = Tcp16Verdict.INVALID_RECONNECTED,
                        measuredRttMs = rttSamples.averageMillis(),
                        errorDetail = "probe opened more than one socket",
                        connectionCount = tracker.connectionCount,
                    )
                }
                outcome
                    .onSuccess { response ->
                        rttSamples += response.elapsedMs
                    }.onFailure { error ->
                        return@withContext target.failureResult(
                            requestIndex = requestIndex,
                            error = error,
                            measuredRttMs = rttSamples.averageMillis(),
                            connectionCount = tracker.connectionCount,
                        )
                    }
            }
            target.result(
                alive = true,
                verdict = Tcp16Verdict.OK,
                measuredRttMs = rttSamples.averageMillis(),
                connectionCount = tracker.connectionCount,
            )
        }

    private fun timeoutFor(rttMs: Long?): Long {
        val baseRtt = rttMs?.coerceAtLeast(MinimumRttHintMs) ?: DefaultTimeoutBaseMs
        return (baseRtt * TimeoutRttMultiplier + TimeoutSafetyMarginMs)
            .coerceIn(MinTimeoutMs, MaxTimeoutMs)
    }

    private fun Tcp16Target.failureResult(
        requestIndex: Int,
        error: Throwable,
        measuredRttMs: Long?,
        connectionCount: Int,
    ): Tcp16ProbeResult {
        val errorLabel = error.tcp16ErrorLabel()
        if (requestIndex == 0) {
            return result(
                alive = false,
                verdict = Tcp16Verdict.DEAD,
                measuredRttMs = measuredRttMs,
                errorDetail = errorLabel,
                connectionCount = connectionCount,
            )
        }
        return if (error.isTcp16ResetLike()) {
            result(
                alive = true,
                verdict = Tcp16Verdict.DETECTED_AT_KB,
                detectedAtKb = requestIndex * PaddingKilobytes,
                measuredRttMs = measuredRttMs,
                errorDetail = "connection closed during padded header train",
                connectionCount = connectionCount,
            )
        } else {
            result(
                alive = true,
                verdict = Tcp16Verdict.ERROR,
                measuredRttMs = measuredRttMs,
                errorDetail = errorLabel,
                connectionCount = connectionCount,
            )
        }
    }

    private fun Tcp16Target.result(
        alive: Boolean,
        verdict: Tcp16Verdict,
        detectedAtKb: Int? = null,
        measuredRttMs: Long?,
        errorDetail: String? = null,
        connectionCount: Int,
    ): Tcp16ProbeResult =
        Tcp16ProbeResult(
            targetId = id,
            asn = asn,
            provider = provider,
            ip = ip,
            port = port,
            alive = alive,
            verdict = verdict,
            detectedAtKb = detectedAtKb,
            measuredRttMs = measuredRttMs,
            errorDetail = errorDetail,
            connectionCount = connectionCount,
            tlsClientState = tlsClientStateProvider(),
        )

    companion object {
        private const val RequestCount = 16
        private const val PaddingBytes = 4 * 1024
        private const val PaddingKilobytes = 4
        private const val DefaultConcurrency = 15
        private const val MinimumRttHintMs = 25L
        private const val DefaultTimeoutBaseMs = 500L
        private const val TimeoutRttMultiplier = 4L
        private const val TimeoutSafetyMarginMs = 1_000L
        private const val MinTimeoutMs = 1_500L
        private const val MaxTimeoutMs = 10_000L
    }
}

interface Tcp16Probe {
    suspend fun runWithRttHint(
        target: Tcp16Target,
        hintRttMs: Long?,
    ): Tcp16ProbeResult
}

data class Tcp16FatHeaderRequest(
    val requestIndex: Int,
    val padding: String?,
    val timeoutMs: Long,
)

data class Tcp16FatHeaderResponse(
    val statusCode: Int,
    val elapsedMs: Long,
)

interface Tcp16RequestRunner {
    fun execute(
        target: Tcp16Target,
        request: Tcp16FatHeaderRequest,
        tracker: Tcp16ConnectionTracker,
    ): Tcp16FatHeaderResponse
}

typealias Tcp16OkHttpClientBuilder = (OkHttpClient.Builder.() -> Unit) -> OkHttpClient

class Tcp16ConnectionTracker {
    private val connectStarts = AtomicInteger()

    val connectionCount: Int
        get() = connectStarts.get()

    fun recordConnectStart() {
        connectStarts.incrementAndGet()
    }

    fun eventListener(): EventListener =
        object : EventListener() {
            override fun connectStart(
                call: okhttp3.Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
            ) {
                recordConnectStart()
            }
        }
}

class OkHttpTcp16RequestRunner(
    private val baseClient: OkHttpClient = defaultBaseClient(),
) : Tcp16RequestRunner {
    override fun execute(
        target: Tcp16Target,
        request: Tcp16FatHeaderRequest,
        tracker: Tcp16ConnectionTracker,
    ): Tcp16FatHeaderResponse {
        val client =
            baseClient
                .newBuilder()
                .dns(target.dns())
                .eventListenerFactory { tracker.eventListener() }
                .build()
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(target.url())
                    .head()
                    .header("Host", target.sni ?: target.ip)
                    .header("Connection", "keep-alive")
                    .apply {
                        request.padding?.let { padding -> header("X-Pad", padding) }
                    }.build(),
            )
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        val startedNanos = System.nanoTime()
        call.execute().use { response ->
            val elapsedMs = ((System.nanoTime() - startedNanos) / NanosPerMillis).coerceAtLeast(1)
            return Tcp16FatHeaderResponse(
                statusCode = response.code,
                elapsedMs = elapsedMs,
            )
        }
    }

    private fun Tcp16Target.url(): String {
        val scheme = if (port == HttpsPort) "https" else "http"
        return "$scheme://${sni ?: ip}:$port/"
    }

    private fun Tcp16Target.dns(): Dns = TargetDns(ip = ip, sni = sni)

    companion object {
        private const val HttpsPort = 443
        private const val NanosPerMillis = 1_000_000L
        private val trustManager: X509TrustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        private val sslContext: SSLContext =
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }

        fun withClientBuilder(clientBuilder: Tcp16OkHttpClientBuilder): OkHttpTcp16RequestRunner =
            OkHttpTcp16RequestRunner(baseClient = defaultBaseClient(clientBuilder))

        private fun defaultBaseClient(clientBuilder: Tcp16OkHttpClientBuilder? = null): OkHttpClient {
            val configure: OkHttpClient.Builder.() -> Unit = {
                connectionPool(ConnectionPool(1, Long.MAX_VALUE, TimeUnit.NANOSECONDS))
                protocols(listOf(Protocol.HTTP_1_1))
                retryOnConnectionFailure(false)
                followRedirects(false)
                followSslRedirects(false)
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
            }
            return clientBuilder?.invoke(configure) ?: OkHttpClient.Builder().apply(configure).build()
        }
    }
}

private data class TargetDns(
    private val ip: String,
    private val sni: String?,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (sni != null && hostname.equals(sni, ignoreCase = true)) {
            listOf(InetAddress.getByName(ip))
        } else {
            Dns.SYSTEM.lookup(hostname)
        }
}

private fun List<Long>.averageMillis(): Long? =
    if (isEmpty()) {
        null
    } else {
        average().roundToLong()
    }

private fun Throwable.isTcp16ResetLike(): Boolean =
    this is EOFException ||
        this is SocketException ||
        this is SocketTimeoutException ||
        message.orEmpty().lowercase(Locale.US).let { message ->
            "closed" in message || "reset" in message || "unexpected end" in message
        }

private fun Throwable.tcp16ErrorLabel(): String =
    when (this) {
        is SocketTimeoutException -> "timeout"
        is SSLHandshakeException -> "tls handshake failed"
        is SocketException -> message ?: "socket error"
        is EOFException -> "unexpected eof"
        is IOException -> message ?: "i/o error"
        else -> message ?: javaClass.simpleName
    }
