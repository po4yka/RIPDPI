package com.poyka.ripdpi.debug

import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import java.net.InetAddress
import java.net.URI
import java.util.Base64

const val PacketSmokeMapDnsAddress = "198.18.0.53"
const val PacketSmokeMapDnsPort = 53
const val PacketSmokeFaultBootstrapIp = "203.0.113.1"

private const val AdGuardUnfilteredHost = "unfiltered.adguard-dns.com"
private const val AdGuardUnfilteredDoHUrl = "https://unfiltered.adguard-dns.com/dns-query"
private const val AdGuardUnfilteredDnsCryptStamp =
    "sdns://AQMAAAAAAAAAEjk0LjE0MC4xNC4xNDA6NTQ0MyC16ETWuDo-PhJo62gfvqcN48X6aNvWiBQdvy7AZrLa-iUyLmRuc2NyeXB0LnVuZmlsdGVyZWQubnMxLmFkZ3VhcmQuY29t"
private val AdGuardUnfilteredBootstrapIps = listOf("94.140.14.140", "94.140.14.141")

data class PacketSmokeEncryptedDnsPreset(
    val providerId: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val bootstrapIps: List<String>,
    val dohUrl: String,
    val dnscryptProviderName: String,
    val dnscryptPublicKey: String,
    val expectedResolverEndpoint: String,
)

data class PacketSmokeDnsCryptStamp(
    val host: String,
    val port: Int,
    val providerName: String,
    val publicKey: String,
    val bootstrapIps: List<String>,
)

data class DebugDnsProbeDecodedResponse(
    val requestId: Int,
    val rcode: Int,
    val answers: List<String>,
)

data class DebugProbeFailure(
    val errorClass: String,
    val errorMessage: String?,
)

object PacketSmokeEncryptedDnsPresets {
    fun success(protocol: String): PacketSmokeEncryptedDnsPreset {
        val normalizedProtocol = protocol.trim().lowercase()
        return when (normalizedProtocol) {
            EncryptedDnsProtocolDoh ->
                PacketSmokeEncryptedDnsPreset(
                    providerId = DnsProviderCustom,
                    protocol = EncryptedDnsProtocolDoh,
                    host = AdGuardUnfilteredHost,
                    port = 443,
                    tlsServerName = AdGuardUnfilteredHost,
                    bootstrapIps = AdGuardUnfilteredBootstrapIps,
                    dohUrl = AdGuardUnfilteredDoHUrl,
                    dnscryptProviderName = "",
                    dnscryptPublicKey = "",
                    expectedResolverEndpoint = AdGuardUnfilteredDoHUrl,
                )

            EncryptedDnsProtocolDot ->
                PacketSmokeEncryptedDnsPreset(
                    providerId = DnsProviderCustom,
                    protocol = EncryptedDnsProtocolDot,
                    host = AdGuardUnfilteredHost,
                    port = 853,
                    tlsServerName = AdGuardUnfilteredHost,
                    bootstrapIps = AdGuardUnfilteredBootstrapIps,
                    dohUrl = "",
                    dnscryptProviderName = "",
                    dnscryptPublicKey = "",
                    expectedResolverEndpoint = "$AdGuardUnfilteredHost:853",
                )

            EncryptedDnsProtocolDoq ->
                PacketSmokeEncryptedDnsPreset(
                    providerId = DnsProviderCustom,
                    protocol = EncryptedDnsProtocolDoq,
                    host = AdGuardUnfilteredHost,
                    port = 853,
                    tlsServerName = AdGuardUnfilteredHost,
                    bootstrapIps = AdGuardUnfilteredBootstrapIps,
                    dohUrl = "",
                    dnscryptProviderName = "",
                    dnscryptPublicKey = "",
                    expectedResolverEndpoint = "$AdGuardUnfilteredHost:853",
                )

            EncryptedDnsProtocolDnsCrypt -> {
                val stamp = decodeDnsCryptStamp(AdGuardUnfilteredDnsCryptStamp)
                PacketSmokeEncryptedDnsPreset(
                    providerId = DnsProviderCustom,
                    protocol = EncryptedDnsProtocolDnsCrypt,
                    host = stamp.host,
                    port = stamp.port,
                    tlsServerName = "",
                    bootstrapIps = stamp.bootstrapIps,
                    dohUrl = "",
                    dnscryptProviderName = stamp.providerName,
                    dnscryptPublicKey = stamp.publicKey,
                    expectedResolverEndpoint = "${stamp.host}:${stamp.port}",
                )
            }

            else -> error("Unsupported encrypted DNS protocol: $protocol")
        }
    }

    fun fault(protocol: String): PacketSmokeEncryptedDnsPreset =
        success(protocol).copy(bootstrapIps = listOf(PacketSmokeFaultBootstrapIp))
}

fun decodeDnsCryptStamp(value: String): PacketSmokeDnsCryptStamp {
    val payload = value.removePrefix("sdns://")
    require(payload.isNotBlank()) { "DNSCrypt stamp payload must not be blank" }
    val bytes = Base64.getUrlDecoder().decode(payload)
    require(bytes.isNotEmpty()) { "DNSCrypt stamp payload is empty" }
    require(bytes[0].toInt() == 0x01) { "Unsupported DNS stamp protocol byte: ${bytes[0].toInt()}" }

    var index = 1 + 8
    val serverAddress = readLengthPrefixedString(bytes, index)
    index += 1 + serverAddress.length
    val publicKeyBytes = readLengthPrefixedBytes(bytes, index)
    index += 1 + publicKeyBytes.size
    val providerName = readLengthPrefixedString(bytes, index)

    val (host, port) = parseHostAndPort(serverAddress)
    val bootstrapIps = host.takeIf(::isIpLiteral)?.let(::listOf).orEmpty()
    return PacketSmokeDnsCryptStamp(
        host = host,
        port = port,
        providerName = providerName,
        publicKey = publicKeyBytes.joinToString(separator = "") { "%02x".format(it) },
        bootstrapIps = bootstrapIps,
    )
}

object DebugDnsPacketCodec {
    fun buildQuery(
        hostname: String,
        requestId: Int,
    ): ByteArray {
        require(hostname.isNotBlank()) { "hostname must not be blank" }
        val output = ArrayList<Byte>()
        output.writeU16(requestId)
        output.writeU16(0x0100)
        output.writeU16(1)
        output.writeU16(0)
        output.writeU16(0)
        output.writeU16(0)
        hostname.split('.').forEach { label ->
            require(label.isNotEmpty()) { "hostname contains an empty label: $hostname" }
            require(label.length <= 63) { "hostname label exceeds 63 octets: $label" }
            output.add(label.length.toByte())
            label.encodeToByteArray().forEach(output::add)
        }
        output.add(0)
        output.writeU16(1)
        output.writeU16(1)
        return output.toByteArray()
    }

    fun decodeResponse(
        packet: ByteArray,
        expectedRequestId: Int,
    ): DebugDnsProbeDecodedResponse {
        require(packet.size >= 12) { "DNS packet too short: ${packet.size}" }
        val requestId = packet.readU16(0)
        require(requestId == expectedRequestId) {
            "Unexpected DNS request ID: expected=$expectedRequestId actual=$requestId"
        }
        val flags = packet.readU16(2)
        val rcode = flags and 0x000F
        val questionCount = packet.readU16(4)
        val answerCount = packet.readU16(6)
        var offset = 12
        repeat(questionCount) {
            offset = skipName(packet, offset)
            require(offset + 4 <= packet.size) { "DNS question section truncated" }
            offset += 4
        }

        val answers = mutableListOf<String>()
        repeat(answerCount) {
            offset = skipName(packet, offset)
            require(offset + 10 <= packet.size) { "DNS answer section truncated" }
            val type = packet.readU16(offset)
            val dataLength = packet.readU16(offset + 8)
            offset += 10
            require(offset + dataLength <= packet.size) { "DNS rdata section truncated" }
            when (type) {
                1 ->
                    if (dataLength == 4) {
                        answers +=
                            InetAddress
                                .getByAddress(packet.copyOfRange(offset, offset + 4))
                                .hostAddress
                    }

                28 ->
                    if (dataLength == 16) {
                        answers +=
                            InetAddress
                                .getByAddress(packet.copyOfRange(offset, offset + 16))
                                .hostAddress
                    }

                5, 12 -> answers += readName(packet, offset).name
            }
            offset += dataLength
        }

        return DebugDnsProbeDecodedResponse(
            requestId = requestId,
            rcode = rcode,
            answers = answers,
        )
    }
}

fun Throwable.toDebugProbeFailure(): DebugProbeFailure =
    DebugProbeFailure(
        errorClass = javaClass.name,
        errorMessage = message,
    )

private fun readLengthPrefixedString(
    bytes: ByteArray,
    index: Int,
): String = readLengthPrefixedBytes(bytes, index).decodeToString()

private fun readLengthPrefixedBytes(
    bytes: ByteArray,
    index: Int,
): ByteArray {
    require(index < bytes.size) { "Missing length-prefixed value at index $index" }
    val length = bytes[index].toUByte().toInt()
    val start = index + 1
    val end = start + length
    require(end <= bytes.size) { "Length-prefixed value at index $index overruns the stamp payload" }
    return bytes.copyOfRange(start, end)
}

private fun parseHostAndPort(value: String): Pair<String, Int> {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "DNS stamp server address must not be blank" }
    val uri = URI("dns://$normalized")
    val host = uri.host ?: normalized.substringBefore(':')
    val port = uri.port.takeIf { it > 0 } ?: error("DNS stamp server port is missing: $value")
    return host to port
}

private fun isIpLiteral(value: String): Boolean {
    val normalized = value.trim()
    return normalized.all { it.isDigit() || it == '.' } || ':' in normalized
}

private fun MutableList<Byte>.writeU16(value: Int) {
    add(((value ushr 8) and 0xFF).toByte())
    add((value and 0xFF).toByte())
}

private fun ByteArray.readU16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private data class DnsNameRead(
    val name: String,
    val nextOffset: Int,
)

private fun skipName(
    packet: ByteArray,
    offset: Int,
): Int = readName(packet, offset).nextOffset

private fun readName(
    packet: ByteArray,
    offset: Int,
): DnsNameRead {
    val labels = mutableListOf<String>()
    var cursor = offset
    var consumedOffset = offset
    var jumped = false
    var jumps = 0

    while (true) {
        require(cursor < packet.size) { "DNS name exceeds packet bounds" }
        val length = packet[cursor].toInt() and 0xFF
        when {
            length == 0 -> {
                if (!jumped) {
                    consumedOffset = cursor + 1
                }
                return DnsNameRead(
                    name = labels.joinToString("."),
                    nextOffset = consumedOffset,
                )
            }

            length and 0xC0 == 0xC0 -> {
                require(cursor + 1 < packet.size) { "DNS compression pointer is truncated" }
                val pointer = ((length and 0x3F) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
                require(pointer < packet.size) { "DNS compression pointer exceeds packet size" }
                if (!jumped) {
                    consumedOffset = cursor + 2
                    jumped = true
                }
                cursor = pointer
                jumps += 1
                require(jumps < 16) { "DNS compression pointer recursion limit exceeded" }
            }

            else -> {
                val labelStart = cursor + 1
                val labelEnd = labelStart + length
                require(labelEnd <= packet.size) { "DNS label exceeds packet bounds" }
                labels += packet.copyOfRange(labelStart, labelEnd).decodeToString()
                cursor = labelEnd
                if (!jumped) {
                    consumedOffset = cursor
                }
            }
        }
    }
}
