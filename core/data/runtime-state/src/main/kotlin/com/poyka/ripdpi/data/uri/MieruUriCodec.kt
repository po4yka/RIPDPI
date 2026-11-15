package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.MieruHandshakeDefault
import com.poyka.ripdpi.data.MieruHandshakeNoWait
import com.poyka.ripdpi.data.MieruHandshakeStandard
import com.poyka.ripdpi.data.MieruTrafficPattern
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayMieruMtuDefault
import com.poyka.ripdpi.data.RelayMieruMtuMax
import com.poyka.ripdpi.data.RelayMieruMtuMin
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruMultiplexingLow
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruMultiplexingOff
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
import com.poyka.ripdpi.data.isValidRelayMieruUsername
import com.poyka.ripdpi.data.validateMieruAdvancedSettings
import com.poyka.ripdpi.data.validateNativeRelayProfile
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

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
        if (uri.length > MieruMaximumUriBytes) return null
        val scheme = uri.substringBefore("://").lowercase()
        val body = uri.substringAfter("://")
        return when {
            scheme == "mierus" -> parseSimple(uri)
            '@' in body -> parseLegacy(uri)
            else -> parseStandard(body)
        }
    }

    fun encode(profile: ProxyProfile.Mieru): String? {
        if (runCatching { validateNativeRelayProfile(profile) }.isFailure ||
            profile.mtu !in RelayMieruMtuMin..RelayMieruMtuMax ||
            profile.multiplexing !in
            setOf(
                RelayMieruMultiplexingOff,
                RelayMieruMultiplexingLow,
                RelayMieruMultiplexingMiddle,
                RelayMieruMultiplexingHigh,
            )
        ) {
            return null
        }
        val params =
            (
                listOf(
                    "profile=default",
                    "mtu=${profile.mtu}",
                    "multiplexing=${multiplexingToken(profile.multiplexing)}",
                    "port=${profile.serverPort}",
                    "protocol=${profile.protocol.uppercase()}",
                ) +
                    listOfNotNull(
                        profile.handshakeMode?.let { "handshake-mode=HANDSHAKE_${it.uppercase()}" },
                        profile.trafficPattern?.let {
                            "traffic-pattern=${encodeComponent(
                                MieruTrafficPatternCodec.encodeBase64(it),
                            )}"
                        },
                    )
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
        if (!validKeys(
                query,
                setOf("profile", "mtu", "multiplexing", "port", "protocol", "handshake-mode", "traffic-pattern"),
            )
        ) {
            return null
        }
        if (query.firstValue("profile").isNullOrBlank()) return null
        val (handshake, traffic) = advanced(query) ?: return null
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
        val name = parsed.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: query.firstValue("profile") ?: host
        return profile(
            name,
            host,
            port,
            username,
            password,
            RelayMieruProtocolTcp,
            multiplexing,
            mtu,
            handshake,
            traffic,
        )
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseLegacy(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val (username, password) = userInfo(parsed.rawUserInfo) ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.removeSurrounding("[", "]") ?: return null
        val port = parsed.port.takeIf { it in 1..MieruMaxPort } ?: return null
        val query = queryPairs(parsed.rawQuery) ?: return null
        if (!validKeys(query, setOf("protocol", "mux", "mtu", "handshake-mode", "traffic-pattern"))) return null
        val (handshake, traffic) = advanced(query) ?: return null
        // RIPDPI's Mieru is TCP-only; a UDP or unknown protocol is not importable.
        if (query.firstValue("protocol")?.lowercase().let { it != null && it != RelayMieruProtocolTcp }) return null
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
        return profile(
            name,
            host,
            port,
            username,
            password,
            RelayMieruProtocolTcp,
            multiplexing,
            mtu,
            handshake,
            traffic,
        )
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseStandard(body: String): ProxyProfile? {
        val content = body.trim()
        val paddingCount = content.length - content.trimEnd('=').length
        if (paddingCount > 2 || (paddingCount != 0 && content.length % Base64GroupSize != 0)) return null
        val normalized =
            body
                .trim()
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
        val padding = "=".repeat((Base64GroupSize - normalized.length % Base64GroupSize) % Base64GroupSize)
        val config = Base64.getDecoder().decode(normalized + padding)
        val top = readMieruProtobufFields(config)
        requireFields(
            top,
            MieruConfigVarints,
            MieruConfigBytes,
            setOf(FieldConfigProfiles, FieldConfigSocks5Authentication),
        )
        val profiles =
            top.filter { it.id == FieldConfigProfiles }.mapNotNull { it.bytes }.map(
                ::readMieruProtobufFields,
            )
        val active = top.text(FieldConfigActiveProfile)
        val selected =
            (
                if (active ==
                    null
                ) {
                    profiles.firstOrNull()
                } else {
                    profiles.firstOrNull { it.text(FieldProfileName) == active }
                }
            )
                ?: return null
        requireFields(
            selected,
            setOf(FieldProfileMtu, FieldProfileHandshake),
            setOf(
                FieldProfileName,
                FieldProfileUser,
                FieldProfileServers,
                FieldProfileMultiplexing,
                FieldProfileTrafficPattern,
            ),
            setOf(FieldProfileServers),
        )
        val user =
            selected.firstOrNull { it.id == FieldProfileUser }?.bytes?.let(::readMieruProtobufFields) ?: return null
        requireFields(
            user,
            setOf(FieldUserAllowPrivateIp, FieldUserAllowLoopbackIp),
            setOf(FieldUserName, FieldUserPassword, FieldUserHashedPassword, FieldUserQuotas),
            setOf(FieldUserQuotas),
        )
        val username = user.text(FieldUserName)?.takeIf(::isValidRelayMieruUsername) ?: return null
        val password = user.text(FieldUserPassword)?.takeIf { it.isNotBlank() } ?: return null
        if (!matchingPasswordHash(user.text(FieldUserHashedPassword), username, password)) return null
        val (host, port) =
            selected
                .filter { it.id == FieldProfileServers }
                .mapNotNull { it.bytes }
                .map(::readMieruProtobufFields)
                .firstNotNullOfOrNull(::tcpEndpoint) ?: return null
        val mtuValue = selected.firstOrNull { it.id == FieldProfileMtu }?.number?.takeIf { it != 0L }
        val mtu = mtuValue?.let { if (it in RelayMieruMtuMin..RelayMieruMtuMax) it.toInt() else return null }
        val level =
            selected
                .firstOrNull { it.id == FieldProfileMultiplexing }
                ?.bytes
                ?.let(::readMieruProtobufFields)
                ?.also { requireFields(it, setOf(1), emptySet()) }
                ?.firstOrNull { it.id == FieldMultiplexingLevel }
        val multiplexing =
            when (level?.number ?: LevelDefault) {
                LevelDefault, LevelMiddle -> RelayMieruMultiplexingMiddle
                LevelOff -> RelayMieruMultiplexingOff
                LevelLow -> RelayMieruMultiplexingLow
                LevelHigh -> RelayMieruMultiplexingHigh
                else -> return null
            }
        val handshake =
            selected.firstOrNull { it.id == FieldProfileHandshake }?.let {
                val number = it.number?.takeIf { value -> value in HandshakeDefaultNumber..HandshakeNoWaitNumber }
                handshake(number?.toInt() ?: return null) ?: return null
            }
        val traffic =
            selected.firstOrNull { it.id == FieldProfileTrafficPattern }?.let {
                MieruTrafficPatternCodec.decode(it.bytes ?: return null)
            }
        if (selected.count { it.id == FieldProfileHandshake } > 1 ||
            selected.count { it.id == FieldProfileTrafficPattern } > 1
        ) {
            return null
        }
        val finalMtu = mtu ?: RelayMieruMtuDefault
        val name = selected.text(FieldProfileName)?.takeIf { it.isNotBlank() } ?: host
        return profile(
            name,
            host,
            port,
            username,
            password,
            RelayMieruProtocolTcp,
            multiplexing,
            finalMtu,
            handshake,
            traffic,
        )
    }

    /** Host and first TCP port of one upstream ServerEndpoint, or null when it has no usable TCP binding. */
    private fun tcpEndpoint(server: List<MieruProtoField>): Pair<String, Int>? {
        requireFields(
            server,
            emptySet(),
            setOf(FieldServerIp, FieldServerDomain, FieldServerBindings),
            setOf(FieldServerBindings),
        )
        val host =
            listOf(FieldServerDomain, FieldServerIp)
                .firstNotNullOfOrNull { server.text(it)?.takeIf(String::isNotBlank) } ?: return null
        val bindings =
            server.filter { it.id == FieldServerBindings }.mapNotNull { it.bytes }.map { raw ->
                val fields = readMieruProtobufFields(raw)
                requireFields(fields, setOf(FieldBindingPort, FieldBindingProtocol), setOf(FieldBindingRange))
                require(
                    fields.count {
                        it.id == FieldBindingPort || it.id == FieldBindingRange
                    } == 1,
                ) { "Invalid Mieru port binding" }
                require(
                    fields.firstOrNull { it.id == FieldBindingProtocol }?.number in
                        setOf(ProtoTransportUdp, ProtoTransportTcp),
                ) { "Invalid Mieru transport" }
                val single = fields.firstOrNull { it.id == FieldBindingPort }?.number
                Binding(
                    port = fields.text(FieldBindingRange) ?: single?.toString().orEmpty(),
                    tcp = fields.firstOrNull { it.id == FieldBindingProtocol }?.number == ProtoTransportTcp,
                )
            }
        return firstTcpPort(bindings)?.let { host to it }
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
        return low.takeIf { low in 1..MieruMaxPort && high in low..MieruMaxPort }
    }

    private fun mtu(value: String?): Int? =
        if (value == null) {
            RelayMieruMtuDefault
        } else {
            value.toIntOrNull()?.takeIf { it in RelayMieruMtuMin..RelayMieruMtuMax }
        }

    private fun multiplexingFromToken(value: String?): String? =
        when (value?.uppercase()) {
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
        val username = decode(info.substring(0, separator))?.takeIf(::isValidRelayMieruUsername) ?: return null
        val password = decode(info.substring(separator + 1))?.takeIf { it.isNotBlank() } ?: return null
        return username to password
    }

    /** Decoded query pairs in order, or null on a malformed escape. */
    private fun queryPairs(rawQuery: String?): List<Pair<String, String>>? {
        if (rawQuery != null && rawQuery.count { it == '&' } >= MieruMaximumFields) return null
        val pairs =
            rawQuery
                ?.split('&')
                ?.filter { it.isNotEmpty() }
                ?.map { part ->
                    queryDecode(part.substringBefore('=')) to queryDecode(part.substringAfter('=', ""))
                }.orEmpty()
        return if (pairs.any { it.first == null || it.second == null }) {
            null
        } else {
            pairs.map { requireNotNull(it.first) to requireNotNull(it.second) }
        }
    }

    private fun validKeys(
        query: List<Pair<String, String>>,
        allowed: Set<String>,
    ): Boolean =
        query.all { it.first in allowed } &&
            query.groupingBy { it.first }.eachCount().all { (key, count) ->
                key in setOf("port", "protocol") || count == 1
            }

    private fun advanced(query: List<Pair<String, String>>): Pair<String?, MieruTrafficPattern?>? {
        val raw = query.firstValue("handshake-mode")
        val handshake =
            raw?.let {
                when (it) {
                    "HANDSHAKE_DEFAULT" -> MieruHandshakeDefault
                    "HANDSHAKE_STANDARD" -> MieruHandshakeStandard
                    "HANDSHAKE_NO_WAIT" -> MieruHandshakeNoWait
                    else -> return null
                }
            }
        val traffic = query.firstValue("traffic-pattern")?.let(MieruTrafficPatternCodec::decodeBase64)
        validateMieruAdvancedSettings(handshake, traffic)
        return handshake to traffic
    }

    private fun handshake(value: Int): String? =
        when (value) {
            HandshakeDefaultNumber -> MieruHandshakeDefault
            HandshakeStandardNumber -> MieruHandshakeStandard
            HandshakeNoWaitNumber -> MieruHandshakeNoWait
            else -> null
        }

    private fun List<Pair<String, String>>.firstValue(key: String): String? = firstOrNull { it.first == key }?.second

    private fun queryDecode(value: String): String? = decode(value.replace("+", "%20"))

    private fun decode(value: String): String? = ProxyUriCodec.rfc3986PercentDecode(value)

    private fun encodeComponent(value: String): String = encodeUriComponent(value)

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
        handshakeMode: String?,
        trafficPattern: MieruTrafficPattern?,
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
            handshakeMode = handshakeMode,
            trafficPattern = trafficPattern,
        )

    private fun matchingPasswordHash(
        hash: String?,
        username: String,
        password: String,
    ): Boolean {
        if (hash.isNullOrEmpty()) return true
        return if (hash.length == MieruPasswordHashBytes * MieruHexCharsPerByte &&
            hash.all { it in "0123456789abcdefABCDEF" }
        ) {
            val supplied =
                ByteArray(MieruPasswordHashBytes) {
                    val start = it * MieruHexCharsPerByte
                    hash.substring(start, start + MieruHexCharsPerByte).toInt(MieruHexRadix).toByte()
                }
            val derived = MessageDigest.getInstance("SHA-256").digest((password + '\u0000' + username).toByteArray())
            MessageDigest.isEqual(supplied, derived)
        } else {
            false
        }
    }

    private fun requireFields(
        fields: List<MieruProtoField>,
        varints: Set<Int>,
        bytes: Set<Int>,
        repeated: Set<Int> = emptySet(),
    ) {
        require(
            fields.all { (it.id in varints && it.number != null) || (it.id in bytes && it.bytes != null) },
        ) {
            "Unsupported Mieru protobuf field"
        }
        require(
            fields.groupingBy { it.id }.eachCount().all { (number, count) ->
                number in repeated || count == 1
            },
        ) {
            "Repeated Mieru scalar"
        }
    }
}
