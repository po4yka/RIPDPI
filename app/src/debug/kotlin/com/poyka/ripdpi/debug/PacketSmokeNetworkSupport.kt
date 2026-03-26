package com.poyka.ripdpi.debug

import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

const val PacketSmokeMapDnsAddress = "198.18.0.53"
const val PacketSmokeMapDnsPort = 53
const val PacketSmokeFaultBootstrapIp = "203.0.113.1"
const val PacketSmokePrepareStateFileName = "prepare-state.json"
const val PacketSmokeProbeResultFileName = "probe-result.json"

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

enum class PacketSmokePhase(
    val argumentValue: String,
) {
    SINGLE("single"),
    PREPARE("prepare"),
    ASSERT("assert"),
    ;

    companion object {
        fun fromArgument(value: String?): PacketSmokePhase =
            entries.firstOrNull { it.argumentValue == value?.trim() } ?: SINGLE
    }
}

data class PacketSmokePrepareState(
    val scenarioId: String,
    val deviceProfile: String,
    val mode: String?,
    val status: String,
    val proxySessions: Long,
    val txPackets: Long,
    val rxPackets: Long,
    val txBytes: Long,
    val rxBytes: Long,
    val dnsQueriesTotal: Long,
    val dnsFailuresTotal: Long,
    val restartCount: Int,
    val capturedAtEpochMs: Long,
    val expectedResolverId: String? = null,
    val expectedResolverProtocol: String? = null,
    val expectedResolverEndpoint: String? = null,
    val expectedDnsHost: String? = null,
) {
    fun toJson(): String =
        buildJsonObject {
            put("scenarioId", JsonPrimitive(scenarioId))
            put("deviceProfile", JsonPrimitive(deviceProfile))
            putOptionalString("mode", mode)
            put("status", JsonPrimitive(status))
            put("proxySessions", JsonPrimitive(proxySessions))
            put("txPackets", JsonPrimitive(txPackets))
            put("rxPackets", JsonPrimitive(rxPackets))
            put("txBytes", JsonPrimitive(txBytes))
            put("rxBytes", JsonPrimitive(rxBytes))
            put("dnsQueriesTotal", JsonPrimitive(dnsQueriesTotal))
            put("dnsFailuresTotal", JsonPrimitive(dnsFailuresTotal))
            put("restartCount", JsonPrimitive(restartCount))
            put("capturedAtEpochMs", JsonPrimitive(capturedAtEpochMs))
            putOptionalString("expectedResolverId", expectedResolverId)
            putOptionalString("expectedResolverProtocol", expectedResolverProtocol)
            putOptionalString("expectedResolverEndpoint", expectedResolverEndpoint)
            putOptionalString("expectedDnsHost", expectedDnsHost)
        }.toString()

    companion object {
        fun fromJson(value: String): PacketSmokePrepareState {
            val json = Json.parseToJsonElement(value).jsonObject
            return PacketSmokePrepareState(
                scenarioId = json.requiredString("scenarioId"),
                deviceProfile = json.requiredString("deviceProfile"),
                mode = json.optionalString("mode"),
                status = json.requiredString("status"),
                proxySessions = json.requiredLong("proxySessions"),
                txPackets = json.requiredLong("txPackets"),
                rxPackets = json.requiredLong("rxPackets"),
                txBytes = json.requiredLong("txBytes"),
                rxBytes = json.requiredLong("rxBytes"),
                dnsQueriesTotal = json.requiredLong("dnsQueriesTotal"),
                dnsFailuresTotal = json.requiredLong("dnsFailuresTotal"),
                restartCount = json.requiredInt("restartCount"),
                capturedAtEpochMs = json.requiredLong("capturedAtEpochMs"),
                expectedResolverId = json.optionalString("expectedResolverId"),
                expectedResolverProtocol = json.optionalString("expectedResolverProtocol"),
                expectedResolverEndpoint = json.optionalString("expectedResolverEndpoint"),
                expectedDnsHost = json.optionalString("expectedDnsHost"),
            )
        }
    }
}

data class PacketSmokeRunnerProbeResult(
    val requestId: String,
    val scenarioId: String,
    val probeType: String,
    val host: String,
    val port: Int,
    val ok: Boolean,
    val queryHost: String? = null,
    val rcode: Int? = null,
    val answers: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val localAddress: String? = null,
    val localPort: Int? = null,
    val response: String? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null,
) {
    fun toJson(): String =
        buildJsonObject {
            put("requestId", JsonPrimitive(requestId))
            put("scenarioId", JsonPrimitive(scenarioId))
            put("probeType", JsonPrimitive(probeType))
            put("host", JsonPrimitive(host))
            put("port", JsonPrimitive(port))
            put("ok", JsonPrimitive(ok))
            putOptionalString("queryHost", queryHost)
            putOptionalInt("rcode", rcode)
            put("answers", buildJsonArray { answers.forEach { add(JsonPrimitive(it)) } })
            putOptionalLong("latencyMs", latencyMs)
            putOptionalString("localAddress", localAddress)
            putOptionalInt("localPort", localPort)
            putOptionalString("response", response)
            putOptionalString("errorClass", errorClass)
            putOptionalString("errorMessage", errorMessage)
        }.toString()

    companion object {
        fun fromJson(value: String): PacketSmokeRunnerProbeResult {
            val json = Json.parseToJsonElement(value).jsonObject
            val answersJson = json["answers"]?.jsonArray ?: JsonArray(emptyList())
            return PacketSmokeRunnerProbeResult(
                requestId = json.requiredString("requestId"),
                scenarioId = json.requiredString("scenarioId"),
                probeType = json.requiredString("probeType"),
                host = json.requiredString("host"),
                port = json.requiredInt("port"),
                ok = json["ok"]!!.jsonPrimitive.content == "true",
                queryHost = json.optionalString("queryHost"),
                rcode = json.optionalInt("rcode"),
                answers = buildList {
                    repeat(answersJson.length()) { index ->
                        add(answersJson[index].jsonPrimitive.content)
                    }
                },
                latencyMs = json.optionalLong("latencyMs"),
                localAddress = json.optionalString("localAddress"),
                localPort = json.optionalInt("localPort"),
                response = json.optionalString("response"),
                errorClass = json.optionalString("errorClass"),
                errorMessage = json.optionalString("errorMessage"),
            )
        }
    }
}

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

private fun JsonObjectBuilder.putOptionalString(
    key: String,
    value: String?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putOptionalInt(
    key: String,
    value: Int?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putOptionalLong(
    key: String,
    value: Long?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive.int

private fun JsonObject.optionalInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.optionalLong(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonArray.length(): Int {
    return size
}

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
