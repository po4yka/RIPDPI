package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.diagnostics.DnsMessages
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DnsDatagramReply(
    val answer: DnsProbeAnswer,
    val latencyMs: Long,
)

internal suspend fun exchangeDnsDatagram(
    server: String,
    query: ByteArray,
    timeoutMs: Int,
    port: Int = DnsPort,
): DnsDatagramReply {
    val address =
        NumericIp.parseAddress(server)?.toInetAddress()
            ?: throw UnknownHostException("Expected a numeric UDP DNS resolver")
    return DatagramSocket().use { socket ->
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { socket.close() }
            if (continuation.isActive) {
                try {
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(address, port))
                    val startedAt = System.nanoTime()
                    socket.send(DatagramPacket(query, query.size))
                    val response =
                        DatagramPacket(ByteArray(DnsMessages.MAX_MESSAGE_BYTES), DnsMessages.MAX_MESSAGE_BYTES)
                    socket.receive(response)
                    val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1)
                    val answer = DnsWireBuilder.parseResponse(response.data.copyOf(response.length), query)
                    continuation.resume(DnsDatagramReply(answer.copy(resolver = address.hostAddress), latencyMs))
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }
}

private const val DnsPort = 53
