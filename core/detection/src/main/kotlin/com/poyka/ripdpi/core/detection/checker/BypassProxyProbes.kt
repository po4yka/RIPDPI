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

private fun OutputStream.writeSocks5Greeting() {
    write(byteArrayOf(SocksVersion.toByte(), 0x01, SocksNoAuth.toByte()))
    flush()
}

private fun InputStream.expectSocks5NoAuth() {
    val response = readFully(2)
    check((response[0].toInt() and 0xFF) == SocksVersion)
    check((response[1].toInt() and 0xFF) == SocksNoAuth)
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
    val header = readFully(4)
    if ((header[0].toInt() and 0xFF) != SocksVersion || (header[1].toInt() and 0xFF) != SocksSuccess) {
        return false
    }
    skipSocks5Address(header[3].toInt() and 0xFF)
    readFully(2)
    return true
}

private fun InputStream.readSocks5UdpRelay(proxyHost: String): InetSocketAddress {
    val header = readFully(4)
    check((header[0].toInt() and 0xFF) == SocksVersion)
    check((header[1].toInt() and 0xFF) == SocksSuccess)
    val address =
        when (header[3].toInt() and 0xFF) {
            SocksAddressIpv4 -> InetAddress.getByAddress(readFully(4)).hostAddress
            SocksAddressDomain -> readFully(read()).decodeToString()
            else -> error("unsupported SOCKS5 UDP relay address type")
        }.takeUnless { it == "0.0.0.0" } ?: proxyHost
    val port = readFully(2).toUShortInt()
    return InetSocketAddress(address, port)
}

private fun InputStream.skipSocks5Address(addressType: Int) {
    when (addressType) {
        SocksAddressIpv4 -> readFully(4)
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
    if (packet.size < 4 || packet[2].toInt() != 0) return null
    var offset =
        when (packet[3].toInt() and 0xFF) {
            SocksAddressIpv4 -> 4 + 4 + 2
            SocksAddressDomain -> 4 + 1 + (packet[4].toInt() and 0xFF) + 2
            else -> return null
        }
    if (packet.size < offset + StunHeaderBytes) return null
    val stun = packet.copyOfRange(offset, packet.size)
    val buffer = ByteBuffer.wrap(stun)
    val type = buffer.short.toInt() and 0xFFFF
    val length = buffer.short.toInt() and 0xFFFF
    val cookie = buffer.int
    val tx = ByteArray(StunTransactionIdBytes).also(buffer::get)
    if (type != StunBindingResponse || cookie != StunMagicCookie || !tx.contentEquals(transactionId)) return null

    offset = StunHeaderBytes
    while (offset + 4 <= StunHeaderBytes + length && offset + 4 <= stun.size) {
        val attrType = ByteBuffer.wrap(stun, offset, 2).short.toInt() and 0xFFFF
        val attrLength = ByteBuffer.wrap(stun, offset + 2, 2).short.toInt() and 0xFFFF
        val valueOffset = offset + 4
        if (valueOffset + attrLength > stun.size) return null
        when (attrType) {
            StunXorMappedAddress -> return parseXorMappedIpv4(stun, valueOffset, attrLength)
            StunMappedAddress -> return parseMappedIpv4(stun, valueOffset, attrLength)
        }
        offset = valueOffset + attrLength + ((4 - (attrLength % 4)) % 4)
    }
    return null
}

private fun parseXorMappedIpv4(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String? {
    if (length < 8 || (bytes[offset + 1].toInt() and 0xFF) != SocksAddressIpv4) return null
    val cookieBytes = ByteBuffer.allocate(4).putInt(StunMagicCookie).array()
    val address =
        ByteArray(4) { index ->
            (bytes[offset + 4 + index].toInt() xor cookieBytes[index].toInt()).toByte()
        }
    return InetAddress.getByAddress(address).hostAddress
}

private fun parseMappedIpv4(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String? {
    if (length < 8 || (bytes[offset + 1].toInt() and 0xFF) != SocksAddressIpv4) return null
    return InetAddress.getByAddress(bytes.copyOfRange(offset + 4, offset + 8)).hostAddress
}

private fun Int.toUShortBytes(): ByteArray =
    byteArrayOf(
        ((this ushr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte(),
    )

private fun ByteArray.toUShortInt(): Int = ((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)
