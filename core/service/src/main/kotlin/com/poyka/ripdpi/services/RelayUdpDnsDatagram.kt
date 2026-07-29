package com.poyka.ripdpi.services

import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal fun sendDnsProbePayload(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
    timeoutMillis: Int,
): Boolean {
    require(timeoutMillis > 0) { "UDP probe timeout must be positive" }
    val frame = encodeSocksUdpFrame(target, query)
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    return sendSocksUdpFrame(udp, udpRelay, frame) &&
        awaitMatchingDnsResponse(udp, udpRelay, target, query, deadlineNanos)
}

private fun sendSocksUdpFrame(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    frame: ByteArray,
): Boolean =
    try {
        udp.send(DatagramPacket(frame, frame.size, udpRelay))
        true
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    } catch (_: IOException) {
        false
    }

private fun awaitMatchingDnsResponse(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
    deadlineNanos: Long,
): Boolean {
    var acknowledged = false
    var waiting = true
    while (waiting) {
        val response = receiveBeforeDeadline(udp, deadlineNanos)
        if (response == null) {
            waiting = false
        } else if (isMatchingSocksDnsResponse(response, udpRelay, target, query)) {
            acknowledged = true
            waiting = false
        }
    }
    return acknowledged
}

private fun receiveBeforeDeadline(
    udp: DatagramSocket,
    deadlineNanos: Long,
): DatagramPacket? {
    val remainingNanos = deadlineNanos - System.nanoTime()
    val remainingMillis =
        TimeUnit.NANOSECONDS
            .toMillis(remainingNanos)
            .coerceAtLeast(1L)
            .toInt()
    return if (remainingNanos <= 0L) {
        null
    } else {
        try {
            DatagramPacket(ByteArray(MaxUdpProbeResponseBytes), MaxUdpProbeResponseBytes).also { response ->
                udp.soTimeout = remainingMillis
                udp.receive(response)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (_: IOException) {
            null
        }
    }
}

private fun isMatchingSocksDnsResponse(
    response: DatagramPacket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
): Boolean {
    val datagram = decodeSocksUdpDatagram(response.data, response.length)
    return matchesSocksEndpoint(response.socketAddress, udpRelay) &&
        datagram != null &&
        matchesSocksEndpoint(datagram.target, target) &&
        isMatchingDnsResponse(query, datagram.payload)
}

internal fun encodeSocksUdpFrame(
    target: InetSocketAddress,
    payload: ByteArray,
): ByteArray {
    val address = target.address ?: throw ProtocolException("UDP probe target must be an IP address")
    val addressBytes = address.address
    val addressType = if (addressBytes.size == Ipv4AddressBytes) AddressIpv4 else AddressIpv6
    return ByteArray(SocksUdpAddressOffset + addressBytes.size + SocksPortBytes + payload.size).also { frame ->
        frame[SocksUdpAddressTypeOffset] = addressType
        addressBytes.copyInto(frame, destinationOffset = SocksUdpAddressOffset)
        val portOffset = SocksUdpAddressOffset + addressBytes.size
        frame.writeUnsignedShort(portOffset, target.port)
        payload.copyInto(frame, destinationOffset = portOffset + SocksPortBytes)
    }
}

internal fun decodeSocksUdpPayload(
    frame: ByteArray,
    length: Int,
): ByteArray? = decodeSocksUdpDatagram(frame, length)?.payload

private data class DecodedSocksUdpDatagram(
    val target: InetSocketAddress,
    val payload: ByteArray,
)

private fun decodeSocksUdpDatagram(
    frame: ByteArray,
    length: Int,
): DecodedSocksUdpDatagram? =
    if (hasValidSocksUdpHeader(frame, length)) {
        decodeSocksUdpTargetAndPort(frame, length)?.let { (target, payloadOffset) ->
            frame.copyPayloadOrNull(payloadOffset, length)?.let { payload ->
                DecodedSocksUdpDatagram(target = target, payload = payload)
            }
        }
    } else {
        null
    }

private fun decodeSocksUdpTargetAndPort(
    frame: ByteArray,
    length: Int,
): Pair<InetSocketAddress, Int>? =
    socksAddressLength(frame)?.let { addressLength ->
        val portOffset = SocksUdpAddressOffset + addressLength
        val payloadOffset = portOffset + SocksPortBytes
        if (payloadOffset < length) {
            decodeSocksUdpTarget(frame, portOffset)?.let { target -> target to payloadOffset }
        } else {
            null
        }
    }

private fun decodeSocksUdpTarget(
    frame: ByteArray,
    portOffset: Int,
): InetSocketAddress? =
    when (frame[SocksUdpAddressTypeOffset]) {
        AddressIpv4,
        AddressIpv6,
        -> {
            InetSocketAddress(
                InetAddress.getByAddress(frame.copyOfRange(SocksUdpAddressOffset, portOffset)),
                frame.readUnsignedShort(portOffset),
            )
        }

        AddressDomain -> {
            val domainLength = frame[SocksUdpAddressOffset].toUByte().toInt()
            if (domainLength > 0) {
                val domain =
                    String(
                        frame,
                        SocksUdpAddressOffset + SocksDomainLengthPrefixBytes,
                        domainLength,
                        StandardCharsets.US_ASCII,
                    )
                InetSocketAddress.createUnresolved(domain, frame.readUnsignedShort(portOffset))
            } else {
                null
            }
        }

        else -> {
            null
        }
    }

private fun matchesSocksEndpoint(
    actual: SocketAddress?,
    expected: InetSocketAddress,
): Boolean {
    val actualEndpoint = actual as? InetSocketAddress
    return actualEndpoint?.let { endpoint ->
        val actualAddress = endpoint.address
        val expectedAddress = expected.address
        endpoint.port == expected.port &&
            if (actualAddress != null && expectedAddress != null) {
                actualAddress.address.contentEquals(expectedAddress.address)
            } else {
                endpoint.hostString.equals(expected.hostString, ignoreCase = true)
            }
    } ?: false
}

private fun hasValidSocksUdpHeader(
    frame: ByteArray,
    length: Int,
): Boolean {
    if (length < MinimumSocksUdpFrameBytes) return false
    val reservedBytesValid =
        frame[SocksUdpReservedFirstOffset] == SocksReserved &&
            frame[SocksUdpReservedSecondOffset] == SocksReserved
    val fragmentationDisabled = frame[SocksUdpFragmentOffset] == SocksNoFragment
    return reservedBytesValid && fragmentationDisabled
}

private fun socksAddressLength(frame: ByteArray): Int? =
    when (frame[SocksUdpAddressTypeOffset]) {
        AddressIpv4 -> {
            Ipv4AddressBytes
        }

        AddressIpv6 -> {
            Ipv6AddressBytes
        }

        AddressDomain -> {
            frame
                .getOrNull(SocksUdpAddressOffset)
                ?.toUByte()
                ?.toInt()
                ?.plus(SocksDomainLengthPrefixBytes)
        }

        else -> {
            null
        }
    }

private fun ByteArray.copyPayloadOrNull(
    payloadOffset: Int,
    frameLength: Int,
): ByteArray? = if (payloadOffset < frameLength) copyOfRange(payloadOffset, frameLength) else null

internal fun isMatchingDnsResponse(
    query: ByteArray,
    response: ByteArray,
): Boolean {
    if (response.size < DnsHeaderBytes) return false
    val transactionMatches =
        response.readUnsignedShort(DnsTransactionIdOffset) == query.readUnsignedShort(DnsTransactionIdOffset)
    val isResponse = response.readUnsignedShort(DnsFlagsOffset) and DnsResponseFlag != 0
    val hasQuestion = response.readUnsignedShort(DnsQuestionCountOffset) > 0
    return transactionMatches && isResponse && hasQuestion
}

private fun ByteArray.writeUnsignedShort(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr ByteBits).toByte()
    this[offset + LowByteOffset] = value.toByte()
}

private fun ByteArray.readUnsignedShort(offset: Int): Int =
    (this[offset].toUByte().toInt() shl ByteBits) or this[offset + LowByteOffset].toUByte().toInt()

private const val MaxUdpProbeResponseBytes = 4_096
private const val MinimumSocksUdpFrameBytes = 10
private const val Ipv4AddressBytes = 4
private const val Ipv6AddressBytes = 16
private const val SocksUdpReservedFirstOffset = 0
private const val SocksUdpReservedSecondOffset = 1
private const val SocksUdpFragmentOffset = 2
private const val SocksUdpAddressTypeOffset = 3
private const val SocksUdpAddressOffset = 4
private const val SocksPortBytes = 2
private const val SocksDomainLengthPrefixBytes = 1
private const val SocksNoFragment: Byte = 0
private const val SocksReserved: Byte = 0
private const val AddressIpv4: Byte = 1
private const val AddressDomain: Byte = 3
private const val AddressIpv6: Byte = 4
private const val DnsHeaderBytes = 12
private const val DnsResponseFlag = 0x8000
private const val DnsTransactionIdOffset = 0
private const val DnsFlagsOffset = 2
private const val DnsQuestionCountOffset = 4
private const val ByteBits = 8
private const val LowByteOffset = 1
