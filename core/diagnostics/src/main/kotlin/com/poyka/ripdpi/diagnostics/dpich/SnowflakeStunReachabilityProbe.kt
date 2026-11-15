package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.withTimeoutOrNull
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
        val deadline = deadlineAfter(timeoutMs)
        val status =
            try {
                val destination = withTimeoutOrNull(timeoutMs) { endpoint.resolve() }
                if (destination == null) {
                    PtProbeStatus.TIMEOUT
                } else {
                    socketFactory().use { socket ->
                        val id = transactionId()
                        require(id.size == TransactionIdBytes)
                        socket.withCancellation { exchange(socket, destination, id, deadline) }
                    }
                }
            } catch (_: IOException) {
                PtProbeStatus.NETWORK_ERROR
            }
        return PtProbeTrace(endpoint.toString(), status, elapsedMs(startedAt))
    }

    private fun exchange(
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
        var transmissions = 0
        while (transmissions < MaxTransmissions && deadline - System.nanoTime() > 0) {
            socket.send(outbound)
            val attemptDeadline = minOf(deadline, System.nanoTime() + waitNanos)
            while (attemptDeadline - System.nanoTime() > 0) {
                val waitMs = TimeUnit.NANOSECONDS.toMillis(attemptDeadline - System.nanoTime()).coerceAtLeast(1)
                socket.soTimeout = waitMs.toInt()
                reply.length = buffer.size
                when (receiveReply(socket, reply, destination, id)) {
                    PtProbeStatus.INVALID_RESPONSE -> invalidResponse = true
                    PtProbeStatus.BINDING_RESPONSE -> return PtProbeStatus.BINDING_RESPONSE
                    else -> Unit
                }
            }
            waitNanos *= 2
            transmissions += 1
        }
        return result(invalidResponse)
    }

    private fun receiveReply(
        socket: DatagramSocket,
        reply: DatagramPacket,
        destination: InetSocketAddress,
        id: ByteArray,
    ): PtProbeStatus? {
        try {
            socket.receive(reply)
        } catch (_: SocketTimeoutException) {
            return null
        }
        return classifyReply(reply, destination, id)
    }

    private fun classifyReply(
        reply: DatagramPacket,
        destination: InetSocketAddress,
        id: ByteArray,
    ): PtProbeStatus? {
        if (reply.socketAddress != destination || reply.length < HeaderBytes) return null
        val message = ByteBuffer.wrap(reply.data, reply.offset, reply.length)
        val type = message.unsignedShort()
        val bodyLength = message.unsignedShort()
        return when {
            message.int != MagicCookie || !id.all { it == message.get() } -> {
                null
            }

            reply.length > MaxResponseBytes -> {
                PtProbeStatus.INVALID_RESPONSE
            }

            type != BindingSuccess || bodyLength != message.remaining() || bodyLength % Alignment != 0 -> {
                PtProbeStatus.INVALID_RESPONSE
            }

            !message.hasMappedAddress() -> {
                PtProbeStatus.INVALID_RESPONSE
            }

            else -> {
                PtProbeStatus.BINDING_RESPONSE
            }
        }
    }

    private fun result(invalidResponse: Boolean): PtProbeStatus =
        if (invalidResponse) PtProbeStatus.INVALID_RESPONSE else PtProbeStatus.TIMEOUT

    private fun ByteBuffer.hasMappedAddress(): Boolean {
        var found = false
        var valid = true
        while (valid && hasRemaining()) {
            valid = remaining() >= AttributeHeaderBytes
            if (valid) {
                val type = unsignedShort()
                val length = unsignedShort()
                val paddedLength = (length + Alignment - 1) and -Alignment
                valid = paddedLength <= remaining()
                if (valid) {
                    // The unauthenticated Binding check recognizes the two address attributes.
                    valid = type >= FirstOptionalAttribute || type == XorMappedAddress || type == MappedAddress
                }
                if (valid && type == XorMappedAddress && !found) {
                    found = hasValidXorMappedAddress(length)
                    valid = found
                }
                if (valid) position(position() + paddedLength)
            }
        }
        return valid && found
    }

    private fun ByteBuffer.hasValidXorMappedAddress(length: Int): Boolean =
        if (length < AddressPrefixBytes) {
            false
        } else {
            when (get(position() + FamilyOffset).toInt()) {
                Ipv4Family -> length == Ipv4ValueBytes
                Ipv6Family -> length == Ipv6ValueBytes
                else -> false
            }
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
