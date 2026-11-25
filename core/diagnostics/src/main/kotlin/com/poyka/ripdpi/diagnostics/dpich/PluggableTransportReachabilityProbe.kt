package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.diagnostics.IpProbeMaxPort
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.resolveProbeAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

enum class PtProbeStatus {
    CONNECTED,
    HTTP_RESPONSE,
    BINDING_RESPONSE,
    NOT_CONFIGURED,
    TIMEOUT,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    INVALID_TARGET,
}

data class PtProbeTrace(
    val target: String,
    val status: PtProbeStatus,
    val latencyMs: Long,
    val httpStatusCode: Int? = null,
)

data class PluggableTransportResult(
    val obfs4Tcp: PtProbeTrace,
    val snowflakeBroker: PtProbeTrace,
    val snowflakeStun: PtProbeTrace,
    val meekHead: PtProbeTrace,
)

fun interface PtSubprobe {
    suspend fun run(timeoutMs: Long): PtProbeTrace
}

class PluggableTransportReachabilityProbe(
    private val obfs4Probe: PtSubprobe = Obfs4ReachabilityProbe(),
    private val snowflakeBrokerProbe: PtSubprobe = SnowflakeBrokerReachabilityProbe(),
    private val snowflakeStunProbe: PtSubprobe = SnowflakeStunReachabilityProbe(),
    private val meekProbe: PtSubprobe = MeekReachabilityProbe(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(timeoutMs: Long = DefaultTimeoutMs): PluggableTransportResult =
        coroutineScope {
            val obfs4 = async { runLeg { obfs4Probe.run(timeoutMs) } }
            val broker = async { runLeg { snowflakeBrokerProbe.run(timeoutMs) } }
            val stun = async { runLeg { snowflakeStunProbe.run(timeoutMs) } }
            val meek = async { runLeg { meekProbe.run(timeoutMs) } }
            PluggableTransportResult(obfs4.await(), broker.await(), stun.await(), meek.await())
        }

    private suspend fun runLeg(block: suspend () -> PtProbeTrace): PtProbeTrace =
        runCatching {
            withContext(ioDispatcher) { block() }
        }.getOrElse { error ->
            error.throwIfCancellation()
            PtProbeTrace("", PtProbeStatus.NETWORK_ERROR, 0)
        }

    private companion object {
        private const val DefaultTimeoutMs = 8_000L
    }
}

class Obfs4ReachabilityProbe(
    private val bridges: List<PtEndpoint> = DefaultObfs4Bridges,
    private val socketFactory: () -> Socket = { Socket() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace {
        val usable =
            bridges
                .asSequence()
                .filterNot(PtEndpoint::isDocumentationAddress)
                .take(MaxTargets)
                .toList()
        if (usable.isEmpty()) return PtProbeTrace("", PtProbeStatus.NOT_CONFIGURED, 0)

        val deadlineNanos = deadlineAfter(timeoutMs)
        var last = PtProbeTrace("", PtProbeStatus.TIMEOUT, 0)
        for ((index, bridge) in usable.withIndex()) {
            currentCoroutineContext().ensureActive()
            val remainingMs = remainingMs(deadlineNanos)
            if (remainingMs > 0) {
                last = bridge.tryTcpConnect((remainingMs / (usable.size - index)).coerceAtLeast(1))
            }
            if (remainingMs <= 0 || last.status == PtProbeStatus.CONNECTED) break
        }
        return last
    }

    private suspend fun PtEndpoint.tryTcpConnect(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.nanoTime()
        val deadline = deadlineAfter(timeoutMs)
        val status =
            try {
                withTimeoutOrNull(timeoutMs) {
                    val destination = resolve()
                    socketFactory().use { socket ->
                        socket.withCancellation {
                            val wait = remainingMs(deadline)
                            if (wait <= 0) throw SocketTimeoutException()
                            socket.connect(destination, wait.toInt())
                        }
                    }
                    PtProbeStatus.CONNECTED
                } ?: PtProbeStatus.TIMEOUT
            } catch (_: SocketTimeoutException) {
                PtProbeStatus.TIMEOUT
            } catch (_: IOException) {
                PtProbeStatus.NETWORK_ERROR
            }
        return PtProbeTrace(toString(), status, elapsedMs(startedAt))
    }
}

fun DpiAssetLoader.loadObfs4BridgeEndpoints(): List<PtEndpoint> = loadObfs4Bridges().mapNotNull(String::toPtEndpoint)

fun DpiAssetLoader.loadMeekFrontUrls(): List<String> = loadMeekFronts()

private fun String.toPtEndpoint(): PtEndpoint? {
    val authority = trim().substringBefore(' ')
    val port = authority.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..IpProbeMaxPort } ?: return null
    val host =
        authority.substringBeforeLast(':', "").removeSurrounding("[", "]").takeIf(String::isNotBlank)
    return host?.let { PtEndpoint(it, port) }
}

class SnowflakeBrokerReachabilityProbe(
    private val brokerUrl: String = DefaultSnowflakeBrokerUrl,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace =
        probeHttpEndpoint(brokerUrl, clientBuilder { endpointTimeout(timeoutMs) }, timeoutMs) {
            post("{}".toRequestBody(JsonMediaType))
        }

    private companion object {
        private const val DefaultSnowflakeBrokerUrl = "https://snowflake-broker.torproject.net/"
        private val JsonMediaType = "application/json".toMediaType()
    }
}

class MeekReachabilityProbe(
    private val fronts: List<String> = DefaultMeekFronts,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace {
        val targets = fronts.take(MaxTargets)
        if (targets.isEmpty()) return PtProbeTrace("", PtProbeStatus.NOT_CONFIGURED, 0)

        val deadline = deadlineAfter(timeoutMs)
        val client = clientBuilder { endpointTimeout(timeoutMs) }
        var last = PtProbeTrace("", PtProbeStatus.TIMEOUT, 0)
        for ((index, front) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            val remaining = remainingMs(deadline)
            if (remaining > 0) {
                val attemptMs = (remaining / (targets.size - index)).coerceAtLeast(1)
                last = probeHttpEndpoint(front, client, attemptMs) { head() }
            }
            if (remaining <= 0 || last.status == PtProbeStatus.HTTP_RESPONSE) break
        }
        return last
    }
}

private fun OkHttpClient.Builder.endpointTimeout(timeoutMs: Long) {
    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    followRedirects(false)
    followSslRedirects(false)
}

private suspend fun probeHttpEndpoint(
    target: String,
    client: OkHttpClient,
    timeoutMs: Long,
    configure: Request.Builder.() -> Unit,
): PtProbeTrace {
    val url = target.toHttpUrlOrNull() ?: return PtProbeTrace("", PtProbeStatus.INVALID_TARGET, 0)
    return if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        PtProbeTrace("", PtProbeStatus.INVALID_TARGET, 0)
    } else {
        probeHttpRequest(url, client, timeoutMs, configure)
    }
}

private suspend fun probeHttpRequest(
    url: okhttp3.HttpUrl,
    client: OkHttpClient,
    timeoutMs: Long,
    configure: Request.Builder.() -> Unit,
): PtProbeTrace {
    val endpoint =
        url
            .newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
    val startedAt = System.nanoTime()
    return try {
        val request =
            Request
                .Builder()
                .url(url)
                .apply(configure)
                .build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.executeAsync().use { response ->
            PtProbeTrace(endpoint, PtProbeStatus.HTTP_RESPONSE, elapsedMs(startedAt), response.code)
        }
    } catch (_: InterruptedIOException) {
        PtProbeTrace(endpoint, PtProbeStatus.TIMEOUT, elapsedMs(startedAt))
    } catch (_: IOException) {
        PtProbeTrace(endpoint, PtProbeStatus.NETWORK_ERROR, elapsedMs(startedAt))
    }
}

private const val MaxTargets = 8

internal fun deadlineAfter(timeoutMs: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

internal fun remainingMs(deadline: Long): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())

internal fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

internal suspend fun <T> Closeable.withCancellation(action: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        if (continuation.isActive) {
            val result = runCatching(action)
            val failure = result.exceptionOrNull()
            if (failure != null && failure !is Exception) throw failure
            continuation.resumeWith(result)
        }
    }

data class PtEndpoint(
    val host: String,
    val port: Int,
) {
    internal suspend fun resolve(): InetSocketAddress = InetSocketAddress(resolveProbeAddress(host), port)

    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    fun isDocumentationAddress(): Boolean =
        DocumentationPrefixes.any { prefix -> host.startsWith(prefix) && host.count { it == '.' } == IPv4Dots }
}

private val DocumentationPrefixes = listOf("192.0.2.", "198.51.100.", "203.0.113.")
private const val IPv4Dots = 3

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}

private val DefaultObfs4Bridges =
    listOf(
        PtEndpoint("192.0.2.10", 443),
        PtEndpoint("192.0.2.11", 443),
        PtEndpoint("192.0.2.12", 443),
    )

private val DefaultMeekFronts =
    listOf(
        "https://ajax.aspnetcdn.com/",
        "https://www.fastly.com/",
        "https://d2zfqthxsdq309.cloudfront.net/",
    )
