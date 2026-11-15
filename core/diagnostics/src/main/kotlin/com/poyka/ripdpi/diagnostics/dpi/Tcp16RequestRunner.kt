package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

data class Tcp16FatHeaderRequest(
    val requestIndex: Int,
    val padding: String?,
    val timeoutMs: Long,
    val url: HttpUrl,
    val httpAuthority: String,
)

data class Tcp16FatHeaderResponse(
    val statusCode: Int,
    val elapsedMs: Long,
    val connectionClose: Boolean = false,
)

interface Tcp16RequestRunner {
    fun supports(target: Tcp16Target): Boolean = true

    suspend fun execute(
        target: Tcp16Target,
        request: Tcp16FatHeaderRequest,
        tracker: Tcp16ConnectionTracker,
    ): Tcp16FatHeaderResponse
}

typealias Tcp16OkHttpClientBuilder = (OkHttpClient.Builder.() -> Unit) -> OkHttpClient

class Tcp16ConnectionTracker {
    private val sockets = Collections.newSetFromMap(IdentityHashMap<Socket, Boolean>())

    var connectStarts: Int = 0
        private set

    var stage: Tcp16Stage = Tcp16Stage.SETUP
        private set

    /** One pool per target run: a pool shared by concurrent targets evicted their keep-alive sockets. */
    val connectionPool: ConnectionPool = ConnectionPool(1, Long.MAX_VALUE, TimeUnit.NANOSECONDS)

    val connectionCount: Int
        get() = sockets.size

    internal fun beginRequest() {
        stage = Tcp16Stage.SETUP
    }

    /** Closes the idle keep-alive socket so it does not outlive the target run. */
    fun close() {
        connectionPool.evictAll()
    }

    fun eventListener(): EventListener =
        object : EventListener() {
            override fun connectStart(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
            ) {
                stage = Tcp16Stage.CONNECT
                connectStarts++
            }

            override fun secureConnectStart(call: Call) {
                stage = Tcp16Stage.TLS_HANDSHAKE
            }

            override fun connectionAcquired(
                call: Call,
                connection: Connection,
            ) {
                sockets += connection.socket()
            }

            override fun requestHeadersStart(call: Call) {
                stage = Tcp16Stage.WRITE_REQUEST
            }

            override fun requestHeadersEnd(
                call: Call,
                request: Request,
            ) {
                stage = Tcp16Stage.READ_RESPONSE
            }

            override fun requestFailed(
                call: Call,
                ioe: IOException,
            ) {
                stage = Tcp16Stage.WRITE_REQUEST
            }
        }
}

class OkHttpTcp16RequestRunner(
    private val baseClient: OkHttpClient = defaultBaseClient(),
) : Tcp16RequestRunner {
    override fun supports(target: Tcp16Target): Boolean =
        baseClient.connectionSpecs.any { spec -> spec.isTls == (target.transport == Tcp16Transport.HTTPS) }

    override suspend fun execute(
        target: Tcp16Target,
        request: Tcp16FatHeaderRequest,
        tracker: Tcp16ConnectionTracker,
    ): Tcp16FatHeaderResponse {
        val url = request.url
        if (!hasDirectRoute(url)) throw Tcp16RouteUnavailable()
        val client =
            baseClient
                .newBuilder()
                .proxy(Proxy.NO_PROXY)
                .dns(TargetDns(ip = target.ip, host = url.host))
                .connectionPool(tracker.connectionPool)
                .eventListenerFactory { tracker.eventListener() }
                .build()
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(url)
                    .head()
                    .header("Host", request.httpAuthority)
                    .header("Connection", "keep-alive")
                    .apply {
                        request.padding?.let { padding -> header("X-Pad", padding) }
                    }.build(),
            )
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        val startedNanos = System.nanoTime()
        return suspendCancellableCoroutine { continuation ->
            // Cancellation can close the socket even while this dispatcher is blocked in execute().
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            continuation.resumeWith(
                runCatching {
                    call.execute().use { response ->
                        val elapsedMs = ((System.nanoTime() - startedNanos) / NanosPerMillis).coerceAtLeast(1)
                        Tcp16FatHeaderResponse(
                            statusCode = response.code,
                            elapsedMs = elapsedMs,
                            connectionClose =
                                response.headers
                                    .values("Connection")
                                    .any { value ->
                                        value.split(',').any { it.trim().equals("close", ignoreCase = true) }
                                    },
                        )
                    }
                },
            )
        }
    }

    // An HTTP or SOCKS proxy would choose the peer for the URL authority, invalidating the pin.
    private fun hasDirectRoute(url: HttpUrl): Boolean {
        val proxy = baseClient.proxy ?: baseClient.proxySelector.select(url.toUri())?.firstOrNull()
        return proxy == null || proxy.type() == Proxy.Type.DIRECT
    }

    companion object {
        private const val NanosPerMillis = 1_000_000L

        fun withClientBuilder(clientBuilder: Tcp16OkHttpClientBuilder): OkHttpTcp16RequestRunner =
            OkHttpTcp16RequestRunner(baseClient = defaultBaseClient(clientBuilder))

        private fun defaultBaseClient(clientBuilder: Tcp16OkHttpClientBuilder? = null): OkHttpClient {
            val configure: OkHttpClient.Builder.() -> Unit = {
                protocols(listOf(Protocol.HTTP_1_1))
                retryOnConnectionFailure(false)
                followRedirects(false)
                followSslRedirects(false)
                // The synthetic TLS name controls this probe. Keep certificate chain checks.
                hostnameVerifier { _, _ -> true }
            }
            return clientBuilder?.invoke(configure) ?: OkHttpClient.Builder().apply(configure).build()
        }
    }
}

internal class Tcp16RouteUnavailable : IOException("TCP16 requires a direct route to the target")

private data class TargetDns(
    private val ip: String,
    private val host: String,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.equals(host, ignoreCase = true)) {
            listOf(requireNotNull(NumericIp.parseAddress(ip)).toInetAddress())
        } else {
            throw UnknownHostException("Unexpected TCP16 route host")
        }
}
