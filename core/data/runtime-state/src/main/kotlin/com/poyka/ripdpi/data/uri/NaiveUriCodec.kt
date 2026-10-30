package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.NaiveExtraHeadersResult
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.normalizeHysteria2PinSha256
import com.poyka.ripdpi.data.parseNaiveExtraHeaders
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

private const val DefaultNaivePort = 443

/**
 * NaiveProxy client links: `naive+https://user:pass@host[:port]/?extra-headers=...#name`.
 *
 * The username is required; an empty or absent password is an authenticated identity with an
 * empty password. The port defaults to 443 and the TLS server name is the host. `padding` is
 * accepted and ignored (the helper always negotiates it). `extra-headers` uses the upstream
 * `Name: value` CRLF format and must satisfy the helper's rules, else the link is rejected.
 * `pinSHA256` (optional, as in Hysteria2 links) pins the server leaf certificate. `naive+quic` is not parsed: the helper cannot run QUIC.
 */
internal object NaiveUriCodec {
    @Suppress("ReturnCount")
    fun parse(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val rawUserInfo = parsed.rawUserInfo?.takeIf { it.isNotBlank() } ?: return null
        val separator = rawUserInfo.indexOf(':')
        val username = decode(if (separator < 0) rawUserInfo else rawUserInfo.substring(0, separator))
        val password = if (separator < 0) "" else decode(rawUserInfo.substring(separator + 1))
        if (username.isBlank()) return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.removeSurrounding("[", "]") ?: return null
        val port = if (parsed.port == -1) DefaultNaivePort else parsed.port.takeIf { it > 0 } ?: return null
        val headers =
            when (val result = parseNaiveExtraHeaders(queryValue(parsed.rawQuery, "extra-headers").orEmpty())) {
                is NaiveExtraHeadersResult.Valid -> result.normalized
                is NaiveExtraHeadersResult.Invalid -> return null
            }
        val pin =
            queryValue(parsed.rawQuery, "pinSHA256")?.takeIf { it.isNotBlank() }?.let {
                normalizeHysteria2PinSha256(it) ?: return null
            }.orEmpty()
        val name = parsed.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: host
        return ProxyProfile.Naive(
            id = UUID.randomUUID().toString(),
            displayName = name,
            groupId = "",
            server = host,
            serverPort = port,
            username = username,
            password = password,
            extraHeaders = headers,
            serverPinSha256 = pin,
        )
    }

    fun encode(profile: ProxyProfile.Naive): String {
        val params =
            listOfNotNull(
                profile.extraHeaders.takeIf { it.isNotEmpty() }?.let { "extra-headers=${encode(it)}" },
                profile.serverPinSha256.takeIf { it.isNotEmpty() }?.let { "pinSHA256=${encode(it)}" },
            )
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val server = profile.server
        val host = if (server.contains(':') && !server.startsWith("[")) "[$server]" else server
        return "naive+https://${encode(profile.username)}:${encode(profile.password)}" +
            "@$host:${profile.serverPort}/$query#${encode(profile.displayName)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun queryValue(
        rawQuery: String?,
        key: String,
    ): String? =
        rawQuery
            ?.split('&')
            ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
            ?.firstOrNull { (name, _) -> name == key }
            ?.second
            ?.let(::decode)
}
