package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DatagramSocketQuicUdpProbe internal constructor(
    private val lookupScope: CoroutineScope = DefaultLookupScope,
    private val resolveAddress: (String) -> InetAddress,
) : QuicUdpProbe {
    constructor() : this(resolveAddress = InetAddress::getByName)

    override suspend fun sendInitial(
        target: String,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeoutMs.toLong()) {
                    exchange(resolve(target), port, packet, timeoutMs)
                } ?: QuicProbeSample(response = null, latencyMs = null)
            } catch (_: SocketTimeoutException) {
                QuicProbeSample(response = null, latencyMs = null)
            }
        }

    private suspend fun exchange(
        address: InetAddress,
        port: Int,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample =
        DatagramSocket().use { socket ->
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                if (continuation.isActive) {
                    try {
                        continuation.resume(exchangeBlocking(socket, address, port, packet, timeoutMs))
                    } catch (error: IOException) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }

    private fun exchangeBlocking(
        socket: DatagramSocket,
        address: InetAddress,
        port: Int,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample {
        socket.connect(address, port)
        socket.soTimeout = timeoutMs
        val startedAt = System.nanoTime()
        socket.send(DatagramPacket(packet, packet.size, address, port))
        val buffer = ByteArray(MaxDatagramBytes)
        val response = DatagramPacket(buffer, buffer.size)
        socket.receive(response)
        return QuicProbeSample(
            response = buffer.copyOf(response.length),
            latencyMs = (System.nanoTime() - startedAt) / NanosPerMillis,
        )
    }

    private suspend fun resolve(target: String): InetAddress {
        NumericIp.parseAddress(target)?.let { return it.toInetAddress() }
        LookupSlots.acquire()
        val pending = lookupScope.async { resolveAddress(target) }
        // Keep admission until the detached lookup finishes, including when its caller is cancelled.
        pending.invokeOnCompletion { LookupSlots.release() }
        return pending.await()
    }

    private companion object {
        private const val MaxDatagramBytes = 2_048
        private const val NanosPerMillis = 1_000_000L
        private const val MaxConcurrentLookups = 2

        // System getaddrinfo can finish after cancellation; bound its workers without delaying the caller.
        private val LookupSlots = Semaphore(MaxConcurrentLookups)
        private val DefaultLookupScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MaxConcurrentLookups))
    }
}
