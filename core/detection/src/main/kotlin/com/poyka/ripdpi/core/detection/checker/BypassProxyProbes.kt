@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom

fun interface MtProtoProber {
    suspend fun canReach(proxyEndpoint: ProxyEndpoint): Boolean
}

fun interface Socks5StunClient {
    suspend fun reflexiveAddress(proxyEndpoint: ProxyEndpoint): String?
}

class Socks5MtProtoProber(
    private val dispatchers: AppCoroutineDispatchers,
    private val connectTimeoutMs: Int = DefaultConnectTimeoutMs,
    private val readTimeoutMs: Int = DefaultReadTimeoutMs,
) : MtProtoProber {
    override suspend fun canReach(proxyEndpoint: ProxyEndpoint): Boolean =
        withContext(dispatchers.io) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(proxyEndpoint.host, proxyEndpoint.port), connectTimeoutMs)
                    socket.soTimeout = readTimeoutMs
                    socket.tcpNoDelay = true
                    socket.outputStream.writeSocks5Greeting()
                    socket.inputStream.expectSocks5NoAuth()
                    socket.outputStream.writeSocks5Connect(TelegramDc2Host, TelegramDcPort)
                    socket.inputStream.readSocks5Reply()
                }
            }.getOrDefault(false)
        }

    private companion object {
        const val TelegramDc2Host = "149.154.167.51"
        const val TelegramDcPort = 443
        const val DefaultConnectTimeoutMs = 3_000
        const val DefaultReadTimeoutMs = 3_000
    }
}

class Socks5UdpAssociateStunClient(
    private val dispatchers: AppCoroutineDispatchers,
    private val timeoutMs: Int = DefaultTimeoutMs,
    private val random: SecureRandom = SecureRandom(),
) : Socks5StunClient {
    override suspend fun reflexiveAddress(proxyEndpoint: ProxyEndpoint): String? =
        withContext(dispatchers.io) {
            runCatching {
                Socket().use { controlSocket ->
                    controlSocket.connect(InetSocketAddress(proxyEndpoint.host, proxyEndpoint.port), timeoutMs)
                    controlSocket.soTimeout = timeoutMs
                    controlSocket.outputStream.writeSocks5Greeting()
                    controlSocket.inputStream.expectSocks5NoAuth()
                    controlSocket.outputStream.writeSocks5UdpAssociate()
                    val relay = controlSocket.inputStream.readSocks5UdpRelay(proxyEndpoint.host)
                    sendStunRequest(relay)
                }
            }.getOrNull()
        }

    private fun sendStunRequest(relay: InetSocketAddress): String? =
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val transactionId = ByteArray(StunTransactionIdBytes).also(random::nextBytes)
            val request = buildStunBindingRequest(transactionId)
            val payload = buildSocks5UdpDatagram(StunHost, StunPort, request)
            socket.send(DatagramPacket(payload, payload.size, relay.address, relay.port))
            val buffer = ByteArray(MaxUdpPacketBytes)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            parseSocks5UdpStunResponse(buffer.copyOf(response.length), transactionId)
        }

    private companion object {
        const val StunHost = "stun.l.google.com"
        const val StunPort = 19_302
        const val DefaultTimeoutMs = 3_000
        const val MaxUdpPacketBytes = 1_500
    }
}

private const val SocksVersion = 0x05
private const val SocksNoAuth = 0x00
private const val SocksCommandConnect = 0x01
private const val SocksCommandUdpAssociate = 0x03
private const val SocksAddressIpv4 = 0x01
private const val SocksAddressDomain = 0x03
private const val SocksSuccess = 0x00
private const val StunBindingRequest = 0x0001
private const val StunBindingResponse = 0x0101
private const val StunMappedAddress = 0x0001
private const val StunXorMappedAddress = 0x0020
private const val StunMagicCookie = 0x2112A442
private const val StunHeaderBytes = 20
private const val StunTransactionIdBytes = 12
private const val SocksHeaderBytes = 4
private const val SocksAddressTypeIndex = 3
private const val SocksUdpAddressStartBytes = 4
private const val SocksPortBytes = 2
private const val Ipv4AddressBytes = 4
private const val StunAttributeHeaderBytes = 4
private const val StunAttributeAlignmentBytes = 4
private const val StunIpv4AddressAttributeBytes = 8
private const val StunAddressFamilyOffset = 1
private const val StunAddressValueOffset = 4
private const val UnsignedByteMask = 0xFF
private const val UnsignedShortMask = 0xFFFF
private const val ByteBits = 8

private fun OutputStream.writeSocks5Greeting() {
    write(byteArrayOf(SocksVersion.toByte(), 0x01, SocksNoAuth.toByte()))
    flush()
}

private fun InputStream.expectSocks5NoAuth() {
    val response = readFully(2)
    check(response[0].unsigned() == SocksVersion)
    check(response[1].unsigned() == SocksNoAuth)
}

private fun OutputStream.writeSocks5Connect(
    host: String,
    port: Int,
) {
    writeSocks5TargetRequest(SocksCommandConnect, host, port)
}

private fun OutputStream.writeSocks5UdpAssociate() {
    writeSocks5TargetRequest(SocksCommandUdpAssociate, "0.0.0.0", 0)
}

private fun OutputStream.writeSocks5TargetRequest(
    command: Int,
    host: String,
    port: Int,
) {
    val address = InetAddress.getByName(host)
    val bytes =
        if (address is Inet4Address) {
            byteArrayOf(SocksAddressIpv4.toByte()) + address.address
        } else {
            val domainBytes = host.encodeToByteArray()
            byteArrayOf(SocksAddressDomain.toByte(), domainBytes.size.toByte()) + domainBytes
        }
    write(byteArrayOf(SocksVersion.toByte(), command.toByte(), 0x00) + bytes + port.toUShortBytes())
    flush()
}

private fun InputStream.readSocks5Reply(): Boolean {
    val header = readFully(SocksHeaderBytes)
    if (header[0].unsigned() != SocksVersion || header[1].unsigned() != SocksSuccess) {
        return false
    }
    skipSocks5Address(header[SocksAddressTypeIndex].unsigned())
    readFully(SocksPortBytes)
    return true
}

private fun InputStream.readSocks5UdpRelay(proxyHost: String): InetSocketAddress {
    val header = readFully(SocksHeaderBytes)
    check(header[0].unsigned() == SocksVersion)
    check(header[1].unsigned() == SocksSuccess)
    val address =
        when (header[SocksAddressTypeIndex].unsigned()) {
            SocksAddressIpv4 -> InetAddress.getByAddress(readFully(Ipv4AddressBytes)).hostAddress
            SocksAddressDomain -> readFully(read()).decodeToString()
            else -> error("unsupported SOCKS5 UDP relay address type")
        }.takeUnless { it == "0.0.0.0" } ?: proxyHost
    val port = readFully(SocksPortBytes).toUShortInt()
    return InetSocketAddress(address, port)
}

private fun InputStream.skipSocks5Address(addressType: Int) {
    when (addressType) {
        SocksAddressIpv4 -> readFully(Ipv4AddressBytes)
        SocksAddressDomain -> readFully(read())
        else -> error("unsupported SOCKS5 address type")
    }
}

private fun InputStream.readFully(size: Int): ByteArray {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(buffer, offset, size - offset)
        check(read > 0) { "unexpected end of stream" }
        offset += read
    }
    return buffer
}

private fun buildStunBindingRequest(transactionId: ByteArray): ByteArray =
    ByteBuffer
        .allocate(StunHeaderBytes)
        .putShort(StunBindingRequest.toShort())
        .putShort(0)
        .putInt(StunMagicCookie)
        .put(transactionId)
        .array()

private fun buildSocks5UdpDatagram(
    host: String,
    port: Int,
    payload: ByteArray,
): ByteArray {
    val hostBytes = host.encodeToByteArray()
    return byteArrayOf(0x00, 0x00, 0x00, SocksAddressDomain.toByte(), hostBytes.size.toByte()) +
        hostBytes +
        port.toUShortBytes() +
        payload
}

private fun parseSocks5UdpStunResponse(
    packet: ByteArray,
    transactionId: ByteArray,
): String? {
    val stunOffset = packet.socks5UdpPayloadOffset() ?: return null
    if (packet.size < stunOffset + StunHeaderBytes) return null
    val stun = packet.copyOfRange(stunOffset, packet.size)
    val buffer = ByteBuffer.wrap(stun)
    val type = buffer.short.toInt() and UnsignedShortMask
    val length = buffer.short.toInt() and UnsignedShortMask
    val cookie = buffer.int
    val tx = ByteArray(StunTransactionIdBytes).also(buffer::get)
    if (type != StunBindingResponse || cookie != StunMagicCookie || !tx.contentEquals(transactionId)) return null

    var attributeOffset = StunHeaderBytes
    while (
        attributeOffset + StunAttributeHeaderBytes <= StunHeaderBytes + length &&
        attributeOffset + StunAttributeHeaderBytes <= stun.size
    ) {
        val attrType = ByteBuffer.wrap(stun, attributeOffset, SocksPortBytes).short.toInt() and UnsignedShortMask
        val attrLength =
            ByteBuffer.wrap(stun, attributeOffset + SocksPortBytes, SocksPortBytes).short.toInt() and
                UnsignedShortMask
        val valueOffset = attributeOffset + StunAttributeHeaderBytes
        if (valueOffset + attrLength > stun.size) return null
        when (attrType) {
            StunXorMappedAddress -> return parseXorMappedIpv4(stun, valueOffset, attrLength)
            StunMappedAddress -> return parseMappedIpv4(stun, valueOffset, attrLength)
        }
        attributeOffset =
            valueOffset + attrLength +
            ((StunAttributeAlignmentBytes - (attrLength % StunAttributeAlignmentBytes)) % StunAttributeAlignmentBytes)
    }
    return null
}

private fun ByteArray.socks5UdpPayloadOffset(): Int? {
    if (size < SocksHeaderBytes || this[2].toInt() != 0) return null
    return when (this[SocksAddressTypeIndex].unsigned()) {
        SocksAddressIpv4 -> {
            SocksUdpAddressStartBytes + Ipv4AddressBytes + SocksPortBytes
        }

        SocksAddressDomain -> {
            SocksUdpAddressStartBytes + 1 + this[SocksUdpAddressStartBytes].unsigned() +
                SocksPortBytes
        }

        else -> {
            null
        }
    }
}

private fun parseXorMappedIpv4(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String? {
    if (length < StunIpv4AddressAttributeBytes ||
        bytes[offset + StunAddressFamilyOffset].unsigned() != SocksAddressIpv4
    ) {
        return null
    }
    val cookieBytes = ByteBuffer.allocate(Ipv4AddressBytes).putInt(StunMagicCookie).array()
    val address =
        ByteArray(Ipv4AddressBytes) { index ->
            (bytes[offset + StunAddressValueOffset + index].toInt() xor cookieBytes[index].toInt()).toByte()
        }
    return InetAddress.getByAddress(address).hostAddress
}

private fun parseMappedIpv4(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String? {
    if (length < StunIpv4AddressAttributeBytes ||
        bytes[offset + StunAddressFamilyOffset].unsigned() != SocksAddressIpv4
    ) {
        return null
    }
    return InetAddress
        .getByAddress(bytes.copyOfRange(offset + StunAddressValueOffset, offset + StunIpv4AddressAttributeBytes))
        .hostAddress
}

private fun Int.toUShortBytes(): ByteArray =
    byteArrayOf(
        ((this ushr ByteBits) and UnsignedByteMask).toByte(),
        (this and UnsignedByteMask).toByte(),
    )

private fun ByteArray.toUShortInt(): Int =
    ((this[0].toInt() and UnsignedByteMask) shl ByteBits) or (this[1].toInt() and UnsignedByteMask)

private fun Byte.unsigned(): Int = toInt() and UnsignedByteMask
