package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
        val usable = bridges.filterNot(PtEndpoint::isDocumentationAddress)
        if (usable.isEmpty()) return PtProbeTrace("", PtProbeStatus.NOT_CONFIGURED, 0)

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last = PtProbeTrace("", PtProbeStatus.TIMEOUT, 0)
        for (bridge in usable) {
            currentCoroutineContext().ensureActive()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMs <= 0) break
            last = bridge.tryTcpConnect(remainingMs)
            if (last.status == PtProbeStatus.CONNECTED) break
        }
        return last
    }

    private fun PtEndpoint.tryTcpConnect(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.nanoTime()
        val status =
            try {
                socketFactory().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs.toInt()) }
                PtProbeStatus.CONNECTED
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
    val port = authority.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val host =
        authority.substringBeforeLast(':', "").removeSurrounding("[", "]").takeIf(String::isNotBlank) ?: return null
    return PtEndpoint(host, port)
}

class SnowflakeBrokerReachabilityProbe(
    private val brokerUrl: String = DefaultSnowflakeBrokerUrl,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace =
        probeHttpEndpoint(brokerUrl, clientBuilder { endpointTimeout(timeoutMs) }) {
            post("{}".toRequestBody(JsonMediaType))
        }

    private companion object {
        private const val DefaultSnowflakeBrokerUrl = "https://snowflake-broker.torproject.net/"
        private val JsonMediaType = "application/json".toMediaType()
    }
}

class SnowflakeStunReachabilityProbe(
    private val endpoint: PtEndpoint = PtEndpoint(DefaultSnowflakeStunHost, DefaultSnowflakeStunPort),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val transactionId: () -> ByteArray = { Random.nextBytes(StunTransactionIdBytes) },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.nanoTime()
        val status =
            try {
                socketFactory().use { socket ->
                    socket.soTimeout = timeoutMs.toInt()
                    val request = stunBindingRequest(transactionId())
                    val destination = InetSocketAddress(endpoint.host, endpoint.port)
                    socket.send(DatagramPacket(request, request.size, destination))
                    val response = DatagramPacket(ByteArray(StunResponseBytes), StunResponseBytes)
                    socket.receive(response)
                    if (response.isBindingSuccessFor(request, destination)) {
                        PtProbeStatus.BINDING_RESPONSE
                    } else {
                        PtProbeStatus.INVALID_RESPONSE
                    }
                }
            } catch (_: SocketTimeoutException) {
                PtProbeStatus.TIMEOUT
            } catch (_: IOException) {
                PtProbeStatus.NETWORK_ERROR
            }
        return PtProbeTrace(endpoint.toString(), status, elapsedMs(startedAt))
    }

    private fun stunBindingRequest(id: ByteArray): ByteArray = StunBindingRequestPrefix + id

    private fun DatagramPacket.isBindingSuccessFor(
        request: ByteArray,
        destination: InetSocketAddress,
    ): Boolean {
        if (length < StunHeaderBytes || address != destination.address || port != destination.port) return false
        val data = data.copyOfRange(offset, offset + length)
        val messageLength =
            ((data[StunMessageLengthOffset].toInt() and UnsignedByteMask) shl StunLengthHighByteShift) or
                (data[StunMessageLengthOffset + 1].toInt() and UnsignedByteMask)
        return data[0] == 0x01.toByte() &&
            data[1] == 0x01.toByte() &&
            messageLength % StunAttributeAlignment == 0 &&
            length == StunHeaderBytes + messageLength &&
            data
                .copyOfRange(StunCookieOffset, StunTransactionIdOffset)
                .contentEquals(StunBindingRequestPrefix.copyOfRange(StunCookieOffset, StunTransactionIdOffset)) &&
            data
                .copyOfRange(StunTransactionIdOffset, StunHeaderBytes)
                .contentEquals(request.copyOfRange(StunTransactionIdOffset, StunHeaderBytes))
    }

    private companion object {
        private const val StunHeaderBytes = 20
        private const val StunMessageLengthOffset = 2
        private const val StunCookieOffset = 4
        private const val StunTransactionIdOffset = 8
        private const val StunLengthHighByteShift = 8
        private const val UnsignedByteMask = 0xFF
        private const val StunResponseBytes = 512
        private const val StunTransactionIdBytes = 12
        private const val StunAttributeAlignment = 4
        private val StunBindingRequestPrefix =
            byteArrayOf(
                0x00,
                0x01,
                0x00,
                0x00,
                0x21,
                0x12,
                0xA4.toByte(),
                0x42,
            )
    }
}

class MeekReachabilityProbe(
    private val fronts: List<String> = DefaultMeekFronts,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace =
        coroutineScope {
            val client = clientBuilder { endpointTimeout(timeoutMs) }
            val results = fronts.map { front -> async { probeHttpEndpoint(front, client) { head() } } }.awaitAll()
            results.firstOrNull { it.status == PtProbeStatus.HTTP_RESPONSE }
                ?: results.firstOrNull()
                ?: PtProbeTrace("", PtProbeStatus.NOT_CONFIGURED, 0)
        }
}

private fun OkHttpClient.Builder.endpointTimeout(timeoutMs: Long) {
    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    followRedirects(false)
    followSslRedirects(false)
}

private fun probeHttpEndpoint(
    target: String,
    client: OkHttpClient,
    configure: Request.Builder.() -> Unit,
): PtProbeTrace {
    val url = target.toHttpUrlOrNull() ?: return PtProbeTrace("", PtProbeStatus.INVALID_TARGET, 0)
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return PtProbeTrace("", PtProbeStatus.INVALID_TARGET, 0)
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
        client.newCall(request).execute().use { response ->
            PtProbeTrace(endpoint, PtProbeStatus.HTTP_RESPONSE, elapsedMs(startedAt), response.code)
        }
    } catch (_: InterruptedIOException) {
        PtProbeTrace(endpoint, PtProbeStatus.TIMEOUT, elapsedMs(startedAt))
    } catch (_: IOException) {
        PtProbeTrace(endpoint, PtProbeStatus.NETWORK_ERROR, elapsedMs(startedAt))
    }
}

private fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

data class PtEndpoint(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    /** True for the RFC 5737 documentation ranges, which are never routable. */
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

private const val DefaultSnowflakeStunHost = "stun.l.google.com"
private const val DefaultSnowflakeStunPort = 19302

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
