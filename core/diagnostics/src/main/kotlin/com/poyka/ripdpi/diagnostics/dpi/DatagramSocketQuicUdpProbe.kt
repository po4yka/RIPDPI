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
        accepts: (ByteArray) -> Boolean,
    ): QuicProbeSample {
        val deadline = System.nanoTime() + timeoutMs * NanosPerMillis
        val address =
            withTimeoutOrNull(timeoutMs.toLong()) {
                resolveProbeAddress(target, lookupScope, resolveAddress)
            } ?: return QuicProbeSample(response = null, latencyMs = null)
        return withContext(Dispatchers.IO) {
            exchange(address, port, packet, deadline, accepts)
        }
    }

    private suspend fun exchange(
        address: InetAddress,
        port: Int,
        packet: ByteArray,
        deadline: Long,
        accepts: (ByteArray) -> Boolean,
    ): QuicProbeSample =
        DatagramSocket().use { socket ->
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                if (continuation.isActive) {
                    try {
                        continuation.resume(exchangeBlocking(socket, address, port, packet, deadline, accepts))
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
        deadline: Long,
        accepts: (ByteArray) -> Boolean,
    ): QuicProbeSample {
        socket.connect(address, port)
        val startedAt = System.nanoTime()
        if (deadline - startedAt <= 0) {
            return QuicProbeSample(response = null, latencyMs = null)
        }
        socket.send(DatagramPacket(packet, packet.size, address, port))
        val buffer = ByteArray(MaxDatagramBytes)
        val response = DatagramPacket(buffer, buffer.size)
        var invalidDatagrams = 0
        var receivedBytes = 0
        while (invalidDatagrams < MaxReceivedDatagrams && receivedBytes < MaxReceivedBytes) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            socket.soTimeout = ((remainingNanos + NanosPerMillis - 1) / NanosPerMillis).toInt()
            response.length = buffer.size
            try {
                socket.receive(response)
            } catch (_: SocketTimeoutException) {
                break
            }
            val receivedAt = System.nanoTime()
            if (deadline - receivedAt <= 0) break
            receivedBytes += response.length
            val payload = buffer.copyOf(response.length)
            if (receivedBytes <= MaxReceivedBytes && accepts(payload)) {
                return QuicProbeSample(
                    response = payload,
                    latencyMs = (receivedAt - startedAt) / NanosPerMillis,
                    invalidDatagrams = invalidDatagrams,
                )
            }
            invalidDatagrams++
        }
        return QuicProbeSample(response = null, latencyMs = null, invalidDatagrams = invalidDatagrams)
    }

    private companion object {
        private const val MaxDatagramBytes = 65_535
        private const val MaxReceivedDatagrams = 32
        private const val MaxReceivedBytes = 256 * 1024
        private const val NanosPerMillis = 1_000_000L
    }
}

internal suspend fun resolveProbeAddress(
    target: String,
    lookupScope: CoroutineScope = DefaultLookupScope,
    resolveAddress: (String) -> InetAddress = InetAddress::getByName,
): InetAddress {
    NumericIp.parseAddress(target)?.let { return it.toInetAddress() }
    LookupSlots.acquire()
    val pending = lookupScope.async { resolveAddress(target) }
    // Keep admission until the detached lookup finishes, including when its caller is cancelled.
    pending.invokeOnCompletion { LookupSlots.release() }
    return pending.await()
}

private const val MaxConcurrentLookups = 2
private val LookupSlots = Semaphore(MaxConcurrentLookups)
private val DefaultLookupScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MaxConcurrentLookups))
