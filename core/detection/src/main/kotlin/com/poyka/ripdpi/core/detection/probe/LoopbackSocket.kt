package com.poyka.ripdpi.core.detection.probe

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory
import kotlin.coroutines.resume

internal val DetectionLoopbackHosts = listOf("127.0.0.1", "::1")

internal fun detectionLoopbackAddress(host: String): InetAddress {
    require(host in DetectionLoopbackHosts) { "Local probes require a numeric loopback address" }
    return InetAddress.getByName(host)
}

internal suspend fun <T> withCancellableLoopbackSocket(block: (Socket) -> T): T =
    Socket(Proxy.NO_PROXY).use { socket ->
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            if (continuation.isActive) continuation.resume(block(socket))
        }
    }

/** Owns the socket of one gRPC channel, including a connect still in progress. */
internal class LoopbackSocketFactory(
    private val endpoint: InetAddress,
    private val connectTimeoutMs: Int,
) : SocketFactory(),
    Closeable {
    private var active: Socket? = null
    private var closed = false

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket {
        if (host != endpoint) throw SocketException("Unexpected loopback RPC destination")
        val socket = newSocket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket(detectionLoopbackAddress(host), port)

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = throw SocketException("Loopback RPC does not use explicit source binding")

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = throw SocketException("Loopback RPC does not use explicit source binding")

    @Synchronized
    private fun newSocket(): Socket {
        if (closed || active?.isClosed == false) throw SocketException("Loopback RPC socket is unavailable")
        return Socket(Proxy.NO_PROXY).also { active = it }
    }

    @Synchronized
    override fun close() {
        closed = true
        active?.close()
    }
}
