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
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeMark

class DatagramSocketQuicUdpProbe internal constructor(
    private val lookupScope: CoroutineScope = DefaultLookupScope,
    private val resolveAddress: (String) -> InetAddress,
) : QuicUdpProbe {
    constructor() : this(resolveAddress = InetAddress::getByName)

    override suspend fun resolve(
        target: String,
        timeoutMs: Int,
    ): InetAddress? = withTimeoutOrNull(timeoutMs.toLong()) { resolveProbeAddress(target, lookupScope, resolveAddress) }

    override suspend fun sendInitial(
        address: InetAddress,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        deadline: TimeMark,
        validate: (ByteArray) -> QuicProbeOutcome,
    ): QuicProbeSample =
        withContext(Dispatchers.IO) {
            if (deadline.hasPassedNow()) {
                QuicProbeSample(QuicProbeOutcome.BUDGET_EXHAUSTED)
            } else {
                exchange(address, port, packet, deadline, validate)
            }
        }

    private suspend fun exchange(
        address: InetAddress,
        port: Int,
        packet: ByteArray,
        deadline: TimeMark,
        validate: (ByteArray) -> QuicProbeOutcome,
    ): QuicProbeSample =
        DatagramSocket().use { socket ->
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                if (continuation.isActive) {
                    try {
                        continuation.resume(exchangeBlocking(socket, address, port, packet, deadline, validate))
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
        deadline: TimeMark,
        validate: (ByteArray) -> QuicProbeOutcome,
    ): QuicProbeSample {
        socket.connect(InetSocketAddress(address, port))
        val startedAt = System.nanoTime()
        if (deadline.hasPassedNow()) {
            return QuicProbeSample(QuicProbeOutcome.BUDGET_EXHAUSTED)
        }
        socket.send(DatagramPacket(packet, packet.size, address, port))
        return receiveReply(socket, deadline, startedAt, validate)
    }

    private fun receiveReply(
        socket: DatagramSocket,
        deadline: TimeMark,
        startedAt: Long,
        validate: (ByteArray) -> QuicProbeOutcome,
    ): QuicProbeSample {
        val buffer = ByteArray(MaxDatagramBytes)
        val response = DatagramPacket(buffer, buffer.size)
        var invalidDatagrams = 0
        var receivedBytes = 0
        var sample: QuicProbeSample? = null
        while (sample == null && invalidDatagrams < MaxReceivedDatagrams && receivedBytes < MaxReceivedBytes) {
            val receivedAt =
                try {
                    receiveBeforeDeadline(socket, response, deadline)
                } catch (_: IOException) {
                    sample = QuicProbeSample(QuicProbeOutcome.TRANSPORT_ERROR, invalidDatagrams = invalidDatagrams)
                    null
                }
            if (receivedAt == null) break
            receivedBytes += response.length
            if (receivedBytes <= MaxReceivedBytes) {
                val outcome = validate(buffer.copyOf(response.length))
                if (outcome == QuicProbeOutcome.UNCORRELATED_RESPONSE) {
                    invalidDatagrams++
                } else {
                    val latency = (receivedAt - startedAt) / NanosPerMillis
                    sample =
                        QuicProbeSample(
                            outcome,
                            latency.takeUnless { outcome == QuicProbeOutcome.VALIDATION_ERROR },
                            invalidDatagrams,
                        )
                }
            }
        }
        val outcome =
            when {
                invalidDatagrams == MaxReceivedDatagrams || receivedBytes >= MaxReceivedBytes -> {
                    QuicProbeOutcome.RECEIVE_LIMIT_REACHED
                }

                invalidDatagrams > 0 -> {
                    QuicProbeOutcome.UNCORRELATED_RESPONSE
                }

                else -> {
                    QuicProbeOutcome.NO_RESPONSE
                }
            }
        return sample ?: QuicProbeSample(outcome, invalidDatagrams = invalidDatagrams)
    }

    private fun receiveBeforeDeadline(
        socket: DatagramSocket,
        response: DatagramPacket,
        deadline: TimeMark,
    ): Long? {
        val remainingNanos = (-deadline.elapsedNow()).inWholeNanoseconds
        return if (remainingNanos <= 0) {
            null
        } else {
            socket.soTimeout = ((remainingNanos + NanosPerMillis - 1) / NanosPerMillis).toInt()
            response.length = response.data.size
            try {
                socket.receive(response)
                System.nanoTime().takeUnless { deadline.hasPassedNow() }
            } catch (_: SocketTimeoutException) {
                null
            }
        }
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
