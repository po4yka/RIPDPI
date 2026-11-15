package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class SnowflakeStunReachabilityProbe(
    private val endpoint: PtEndpoint = PtEndpoint(DefaultHost, DefaultPort),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val transactionId: () -> ByteArray = { ByteArray(TransactionIdBytes).also(Random::nextBytes) },
) : PtSubprobe {
    override suspend fun run(timeoutMs: Long): PtProbeTrace {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val status =
            try {
                val destination = InetSocketAddress(endpoint.host, endpoint.port)
                socketFactory().use { socket ->
                    val id = transactionId()
                    require(id.size == TransactionIdBytes)
                    exchange(socket, destination, id, deadline)
                }
            } catch (_: IOException) {
                PtProbeStatus.NETWORK_ERROR
            }
        return PtProbeTrace(endpoint.toString(), status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
    }

    private suspend fun exchange(
        socket: DatagramSocket,
        destination: InetSocketAddress,
        id: ByteArray,
        deadline: Long,
    ): PtProbeStatus {
        val request =
            ByteBuffer
                .allocate(HeaderBytes)
                .putInt(BindingRequest)
                .putInt(MagicCookie)
                .put(id)
                .array()
        val outbound = DatagramPacket(request, request.size, destination)
        val buffer = ByteArray(MaxResponseBytes + 1)
        val reply = DatagramPacket(buffer, buffer.size)
        var waitNanos = TimeUnit.MILLISECONDS.toNanos(InitialWaitMs)
        var invalidResponse = false
        repeat(MaxTransmissions) {
            currentCoroutineContext().ensureActive()
            if (deadline - System.nanoTime() <= 0) return result(invalidResponse)
            socket.send(outbound)
            val attemptDeadline = minOf(deadline, System.nanoTime() + waitNanos)
            while (attemptDeadline - System.nanoTime() > 0) {
                currentCoroutineContext().ensureActive()
                val waitMs = TimeUnit.NANOSECONDS.toMillis(attemptDeadline - System.nanoTime()).coerceAtLeast(1)
                socket.soTimeout = waitMs.toInt()
                reply.length = buffer.size
                try {
                    socket.receive(reply)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (reply.socketAddress != destination || reply.length < HeaderBytes) continue

                val message = ByteBuffer.wrap(reply.data, reply.offset, reply.length)
                val type = message.unsignedShort()
                val bodyLength = message.unsignedShort()
                if (message.int != MagicCookie || !id.all { it == message.get() }) continue
                if (reply.length > MaxResponseBytes ||
                    type != BindingSuccess ||
                    bodyLength != message.remaining() ||
                    bodyLength % Alignment != 0 ||
                    !message.hasMappedAddress()
                ) {
                    invalidResponse = true
                } else {
                    return PtProbeStatus.BINDING_RESPONSE
                }
            }
            waitNanos *= 2
        }
        return result(invalidResponse)
    }

    private fun result(invalidResponse: Boolean): PtProbeStatus =
        if (invalidResponse) PtProbeStatus.INVALID_RESPONSE else PtProbeStatus.TIMEOUT

    private fun ByteBuffer.hasMappedAddress(): Boolean {
        var found = false
        while (hasRemaining()) {
            if (remaining() < AttributeHeaderBytes) return false
            val type = unsignedShort()
            val length = unsignedShort()
            val paddedLength = (length + Alignment - 1) and -Alignment
            if (paddedLength > remaining()) return false
            // The unauthenticated Binding check recognizes the two address attributes.
            if (type < FirstOptionalAttribute && type != XorMappedAddress && type != MappedAddress) return false
            if (type == XorMappedAddress && !found) {
                if (length < AddressPrefixBytes) return false
                val family = get(position() + FamilyOffset).toInt()
                found =
                    when (family) {
                        Ipv4Family -> length == Ipv4ValueBytes
                        Ipv6Family -> length == Ipv6ValueBytes
                        else -> false
                    }
                if (!found) return false
            }
            position(position() + paddedLength)
        }
        return found
    }

    private fun ByteBuffer.unsignedShort(): Int = short.toInt() and UnsignedShortMask

    private companion object {
        private const val DefaultHost = "stun.l.google.com"
        private const val DefaultPort = 19302
        private const val HeaderBytes = 20
        private const val TransactionIdBytes = 12
        private const val MaxResponseBytes = 512
        private const val InitialWaitMs = 500L
        private const val MaxTransmissions = 7
        private const val MagicCookie = 0x2112a442
        private const val BindingRequest = 0x00010000
        private const val BindingSuccess = 0x0101
        private const val AttributeHeaderBytes = 4
        private const val Alignment = 4
        private const val MappedAddress = 0x0001
        private const val XorMappedAddress = 0x0020
        private const val FirstOptionalAttribute = 0x8000
        private const val FamilyOffset = 1
        private const val AddressPrefixBytes = 4
        private const val Ipv4Family = 1
        private const val Ipv6Family = 2
        private const val Ipv4ValueBytes = 8
        private const val Ipv6ValueBytes = 20
        private const val UnsignedShortMask = 0xFFFF
        private val Random = SecureRandom()
    }
}
