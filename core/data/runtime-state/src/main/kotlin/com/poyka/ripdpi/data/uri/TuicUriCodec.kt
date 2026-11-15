package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.validateTuicProfile
import java.net.URI
import java.util.UUID

/** TUIC v5 share links for the native verified-TLS, datagram-UDP capability set. */
internal object TuicUriCodec {
    @Suppress("ReturnCount")
    fun parse(uri: String): ProxyProfile.Tuic? {
        val parsed = URI(uri)
        if (parsed.path !in listOf("", "/")) return null
        val rawUserInfo = parsed.rawUserInfo ?: return null
        val separator = rawUserInfo.indexOf(':')
        if (separator <= 0) return null
        val uuid = ProxyUriCodec.rfc3986PercentDecode(rawUserInfo.substring(0, separator)) ?: return null
        val password = ProxyUriCodec.rfc3986PercentDecode(rawUserInfo.substring(separator + 1)) ?: return null
        val host = parsed.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: return null
        val port = if (parsed.port == -1) DefaultTuicPort else parsed.port
        val options = queryOptions(parsed.rawQuery) ?: return null
        if (options["udp_relay_mode"]?.lowercase() !in setOf(null, "native")) return null
        for (flag in listOf("allow_insecure", "disable_sni", "reduce_rtt", "zero_rtt_handshake", "zero_rtt")) {
            if (options[flag]?.lowercase() !in setOf(null, "0", "false")) return null
        }
        return ProxyProfile
            .Tuic(
                id = UUID.randomUUID().toString(),
                displayName = parsed.fragment?.takeIf { it.isNotBlank() } ?: host,
                groupId = "",
                server = host,
                serverPort = port,
                uuid = uuid,
                password = password,
                serverName = options["sni"],
                congestionControl = options["congestion_control"]?.lowercase() ?: RelayCongestionControlBbr,
                alpn = options["alpn"].orEmpty(),
            ).also { validateTuicProfile(it) }
    }

    fun encode(profile: ProxyProfile.Tuic): String {
        val query =
            buildList {
                profile.serverName?.let { add("sni=${encodeComponent(it)}") }
                if (profile.alpn.isNotEmpty()) add("alpn=${encodeComponent(profile.alpn)}")
                add("congestion_control=${encodeComponent(profile.congestionControl)}")
                add("udp_relay_mode=native")
            }.joinToString("&")
        val host = profile.server.let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
        return "tuic://${encodeComponent(profile.uuid)}:${encodeComponent(profile.password)}" +
            "@$host:${profile.serverPort}/?$query#${encodeComponent(profile.displayName)}"
    }

    private fun queryOptions(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return runCatching {
            rawQuery.split('&').associate { part ->
                val name = part.substringBefore('=')
                require('=' in part && name in SupportedOptions)
                name to requireNotNull(ProxyUriCodec.rfc3986PercentDecode(part.substringAfter('=')))
            }
        }.getOrNull()
    }

    private fun encodeComponent(value: String): String = encodeUriComponent(value)

    private const val DefaultTuicPort = 443

    private val SupportedOptions =
        setOf(
            "sni",
            "alpn",
            "congestion_control",
            "udp_relay_mode",
            "allow_insecure",
            "disable_sni",
            "reduce_rtt",
            "zero_rtt_handshake",
            "zero_rtt",
        )
}
