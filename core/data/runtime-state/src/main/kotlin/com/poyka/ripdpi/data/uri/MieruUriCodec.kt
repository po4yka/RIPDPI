package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayMieruMtuDefault
import com.poyka.ripdpi.data.RelayMieruMtuMax
import com.poyka.ripdpi.data.RelayMieruMtuMin
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruMultiplexingLow
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruMultiplexingOff
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

private const val MaxPort = 65535
private const val ProtoVarintBits = 7
private const val ProtoVarintMaxBytes = 10
private const val ProtoWireVarint = 0
private const val ProtoWireFixed64 = 1
private const val ProtoWireLen = 2
private const val ProtoWireFixed32 = 5
private const val ProtoTransportTcp = 2L

/**
 * Mieru share links, as the upstream client defines them (`docs/client-install.md`, "Sharing
 * Client Settings").
 *
 * - `mierus://user:pass@host?profile=&mtu=&multiplexing=MULTIPLEXING_*&port=P|A-B&protocol=TCP|UDP`
 *   (`port` and `protocol` repeat in pairs).
 * - `mieru://<base64 protobuf ClientConfig>`. Only the fields RIPDPI uses are read.
 * - `mieru://user:pass@host:port?protocol=&mux=&mtu=`, the earlier RIPDPI form, is still imported.
 *
 * RIPDPI's Mieru is TCP-only, so a link needs at least one TCP binding. The first one is used,
 * and for a range its first port. Invalid `mtu`, multiplexing, protocol, or port values reject
 * the link instead of being replaced by a default.
 */
@Suppress("TooManyFunctions")
internal object MieruUriCodec {
    fun parse(uri: String): ProxyProfile? {
        val scheme = uri.substringBefore("://").lowercase()
        val body = uri.substringAfter("://")
        return when {
            scheme == "mierus" -> parseSimple(uri)
            '@' in body -> parseLegacy(uri)
            else -> parseStandard(body)
        }
    }

    fun encode(profile: ProxyProfile.Mieru): String {
        val params =
            listOf(
                "profile=default",
                "mtu=${profile.mtu}",
                "multiplexing=${multiplexingToken(profile.multiplexing)}",
                "port=${profile.serverPort}",
                "protocol=${profile.protocol.uppercase()}",
            ).joinToString("&")
        return "mierus://${encodeComponent(profile.username)}:${encodeComponent(profile.password)}" +
            "@${bracket(profile.server)}?$params#${encodeComponent(profile.displayName)}"
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseSimple(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val (username, password) = userInfo(parsed.rawUserInfo) ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.removeSurrounding("[", "]") ?: return null
        val query = queryPairs(parsed.rawQuery) ?: return null
        val ports = query.filter { it.first == "port" }.map { it.second }
        val protocols = query.filter { it.first == "protocol" }.map { it.second }
        if (ports.isEmpty() || ports.size != protocols.size) return null
        val bindings =
            ports.zip(protocols).map { (port, protocol) ->
                Binding(port = port, tcp = tcpFlag(protocol) ?: return null)
            }
        val port = firstTcpPort(bindings) ?: return null
        val mtu = mtu(query.firstValue("mtu")) ?: return null
        val multiplexing = multiplexingFromToken(query.firstValue("multiplexing")) ?: return null
        val name = parsed.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: host
        return profile(name, host, port, username, password, RelayMieruProtocolTcp, multiplexing, mtu)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseLegacy(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val (username, password) = userInfo(parsed.rawUserInfo) ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.removeSurrounding("[", "]") ?: return null
        val port = parsed.port.takeIf { it in 1..MaxPort } ?: return null
        val query = queryPairs(parsed.rawQuery) ?: return null
        val protocol =
            when (query.firstValue("protocol")?.lowercase()) {
                null, RelayMieruProtocolTcp -> RelayMieruProtocolTcp
                RelayMieruProtocolUdp -> RelayMieruProtocolUdp
                else -> return null
            }
        val multiplexing =
            when (val value = query.firstValue("mux")?.lowercase()) {
                null -> RelayMieruMultiplexingMiddle

                RelayMieruMultiplexingOff,
                RelayMieruMultiplexingLow,
                RelayMieruMultiplexingMiddle,
                RelayMieruMultiplexingHigh,
                -> value

                else -> return null
            }
        val mtu = mtu(query.firstValue("mtu")) ?: return null
        val name = parsed.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: host
        return profile(name, host, port, username, password, protocol, multiplexing, mtu)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseStandard(body: String): ProxyProfile? {
        val normalized =
            body
                .trim()
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
        val config = Base64.getDecoder().decode(normalized + "=".repeat((4 - normalized.length % 4) % 4))
        val top = readFields(config)
        val profiles = top.filter { it.number == 1 }.mapNotNull { it.bytes }.map(::readFields)
        val active = top.text(2)
        val selected =
            (if (active == null) profiles.firstOrNull() else profiles.firstOrNull { it.text(1) == active })
                ?: return null
        val user = selected.firstOrNull { it.number == 2 }?.bytes?.let(::readFields) ?: return null
        val username = user.text(1)?.takeIf { it.isNotBlank() } ?: return null
        val password = user.text(2)?.takeIf { it.isNotBlank() } ?: return null
        val server = selected.firstOrNull { it.number == 3 }?.bytes?.let(::readFields) ?: return null
        val host = listOf(2, 1).firstNotNullOfOrNull { server.text(it)?.takeIf(String::isNotBlank) } ?: return null
        val bindings =
            server.filter { it.number == 3 }.mapNotNull { it.bytes }.map { raw ->
                val fields = readFields(raw)
                val single = fields.firstOrNull { it.number == 1 }?.varint
                Binding(
                    port = fields.text(3) ?: single?.toString().orEmpty(),
                    tcp = fields.firstOrNull { it.number == 2 }?.varint == ProtoTransportTcp,
                )
            }
        val port = firstTcpPort(bindings) ?: return null
        val mtuValue = selected.firstOrNull { it.number == 4 }?.varint?.takeIf { it != 0L }
        val mtu = mtuValue?.let { if (it in RelayMieruMtuMin..RelayMieruMtuMax) it.toInt() else return null }
        val level =
            selected
                .firstOrNull { it.number == 5 }
                ?.bytes
                ?.let(::readFields)
                ?.firstOrNull { it.number == 1 }
        val multiplexing =
            when (level?.varint ?: 0L) {
                0L, 3L -> RelayMieruMultiplexingMiddle
                1L -> RelayMieruMultiplexingOff
                2L -> RelayMieruMultiplexingLow
                4L -> RelayMieruMultiplexingHigh
                else -> return null
            }
        val finalMtu = mtu ?: RelayMieruMtuDefault
        return profile(host, host, port, username, password, RelayMieruProtocolTcp, multiplexing, finalMtu)
    }

    private class Binding(
        val port: String,
        val tcp: Boolean,
    )

    private fun tcpFlag(protocol: String): Boolean? =
        when (protocol.uppercase()) {
            "TCP" -> true
            "UDP" -> false
            else -> null
        }

    /** First TCP binding wins; a range yields its first port. Null when none, or when that port is invalid. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun firstTcpPort(bindings: List<Binding>): Int? {
        val first = bindings.firstOrNull { it.tcp }?.port ?: return null
        val start = first.substringBefore('-')
        val end = first.substringAfter('-', start)
        val low = start.toIntOrNull() ?: return null
        val high = end.toIntOrNull() ?: return null
        return low.takeIf { low in 1..MaxPort && high in low..MaxPort }
    }

    private fun mtu(value: String?): Int? =
        if (value == null) {
            RelayMieruMtuDefault
        } else {
            value.toIntOrNull()?.takeIf { it in RelayMieruMtuMin..RelayMieruMtuMax }
        }

    private fun multiplexingFromToken(value: String?): String? =
        when (value) {
            null, "MULTIPLEXING_DEFAULT", "MULTIPLEXING_MIDDLE" -> RelayMieruMultiplexingMiddle
            "MULTIPLEXING_OFF" -> RelayMieruMultiplexingOff
            "MULTIPLEXING_LOW" -> RelayMieruMultiplexingLow
            "MULTIPLEXING_HIGH" -> RelayMieruMultiplexingHigh
            else -> null
        }

    private fun multiplexingToken(value: String): String = "MULTIPLEXING_${value.uppercase()}"

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun userInfo(raw: String?): Pair<String, String>? {
        val info = raw?.takeIf { it.isNotBlank() } ?: return null
        val separator = info.indexOf(':')
        if (separator <= 0) return null
        val username = decode(info.substring(0, separator))?.takeIf { it.isNotBlank() } ?: return null
        val password = decode(info.substring(separator + 1))?.takeIf { it.isNotBlank() } ?: return null
        return username to password
    }

    /** Decoded query pairs in order, or null on a malformed escape. */
    private fun queryPairs(rawQuery: String?): List<Pair<String, String>>? =
        rawQuery
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.map { part ->
                val key = part.substringBefore('=')
                key to (decode(part.substringAfter('=', "")) ?: return null)
            }.orEmpty()

    private fun List<Pair<String, String>>.firstValue(key: String): String? = firstOrNull { it.first == key }?.second

    private fun decode(value: String): String? = ProxyUriCodec.rfc3986PercentDecode(value)

    private fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun bracket(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun profile(
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        protocol: String,
        multiplexing: String,
        mtu: Int,
    ): ProxyProfile.Mieru =
        ProxyProfile.Mieru(
            id = UUID.randomUUID().toString(),
            displayName = name,
            groupId = "",
            server = host,
            serverPort = port,
            username = username,
            password = password,
            protocol = protocol,
            multiplexing = multiplexing,
            mtu = mtu,
        )

    private class Field(
        val number: Int,
        val varint: Long?,
        val bytes: ByteArray?,
    )

    private fun List<Field>.text(number: Int): String? =
        firstOrNull { it.number == number }?.bytes?.toString(Charsets.UTF_8)

    /** Minimal protobuf wire reader: varint and length-delimited fields are kept, fixed-width ones skipped. */
    private fun readFields(data: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var pos = 0

        fun varint(): Long {
            var result = 0L
            for (i in 0 until ProtoVarintMaxBytes) {
                require(pos < data.size) { "truncated varint" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl (ProtoVarintBits * i))
                if ((b and 0x80) == 0) return result
            }
            throw IllegalArgumentException("varint too long")
        }
        while (pos < data.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                ProtoWireVarint -> {
                    fields += Field(number, varint(), null)
                }

                ProtoWireLen -> {
                    val length = varint()
                    require(length in 0..(data.size - pos).toLong()) { "bad length" }
                    fields += Field(number, null, data.copyOfRange(pos, pos + length.toInt()))
                    pos += length.toInt()
                }

                ProtoWireFixed64 -> {
                    pos += Long.SIZE_BYTES
                }

                ProtoWireFixed32 -> {
                    pos += Int.SIZE_BYTES
                }

                else -> {
                    throw IllegalArgumentException("unsupported wire type")
                }
            }
            require(pos <= data.size) { "truncated field" }
        }
        return fields
    }
}
