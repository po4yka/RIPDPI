package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.MaxNaiveConcurrency
import com.poyka.ripdpi.data.NaiveExtraHeadersResult
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.normalizeHysteria2PinSha256
import com.poyka.ripdpi.data.parseNaiveExtraHeaders
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

private const val DefaultNaivePort = 443
private const val MaxPort = 65535

/**
 * NaiveProxy client links: `naive+https://user:pass@host[:port]/?extra-headers=...#name`, or
 * `naive+quic://` for HTTP/3.
 *
 * The username is required; an empty or absent password is an authenticated identity with an
 * empty password. The port defaults to 443 and the TLS server name is the host. `padding` is
 * accepted and ignored (the helper always negotiates it). `extra-headers` uses the upstream
 * `Name: value` CRLF format and must satisfy the helper's rules, else the link is rejected.
 * `pinSHA256` (optional, as in Hysteria2 links) pins the server leaf certificate. Only the Rust
 * helper checks a pin and only the upstream client runs HTTP/3 and concurrency, so a link with a pin
 * and either of them is rejected. `insecure-concurrency` (1 to [MaxNaiveConcurrency]) sets the upstream client's
 * parallel connections.
 */
internal object NaiveUriCodec {
    @Suppress("ReturnCount")
    fun parse(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val rawUserInfo = parsed.rawUserInfo?.takeIf { it.isNotBlank() } ?: return null
        val separator = rawUserInfo.indexOf(':')
        val username = decode(if (separator < 0) rawUserInfo else rawUserInfo.substring(0, separator)) ?: return null
        val password = if (separator < 0) "" else decode(rawUserInfo.substring(separator + 1)) ?: return null
        if (username.isBlank()) return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.removeSurrounding("[", "]") ?: return null
        val port = if (parsed.port == -1) DefaultNaivePort else parsed.port.takeIf { it in 1..MaxPort } ?: return null
        val options = parseOptions(parsed) ?: return null
        val name = parsed.rawFragment?.let { decode(it) ?: return null }?.takeIf { it.isNotBlank() } ?: host
        return ProxyProfile.Naive(
            id = UUID.randomUUID().toString(),
            displayName = name,
            groupId = "",
            server = host,
            serverPort = port,
            username = username,
            password = password,
            extraHeaders = options.headers,
            serverPinSha256 = options.pin,
            quic = options.quic,
            concurrency = options.concurrency,
        )
    }

    private class Options(
        val headers: String,
        val pin: String,
        val quic: Boolean,
        val concurrency: Int,
    )

    @Suppress("ReturnCount")
    private fun parseOptions(parsed: URI): Options? {
        val rawHeaders = queryValue(parsed.rawQuery, "extra-headers", malformed = { return null }).orEmpty()
        val headers =
            when (val result = parseNaiveExtraHeaders(rawHeaders)) {
                is NaiveExtraHeadersResult.Valid -> result.normalized
                is NaiveExtraHeadersResult.Invalid -> return null
            }
        val pin =
            queryValue(parsed.rawQuery, "pinSHA256", malformed = { return null })
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    normalizeHysteria2PinSha256(it) ?: return null
                }.orEmpty()
        val quic = parsed.scheme.equals("naive+quic", ignoreCase = true)
        val concurrency =
            queryValue(parsed.rawQuery, "insecure-concurrency", malformed = { return null })
                ?.let { it.toIntOrNull()?.takeIf { value -> value in 1..MaxNaiveConcurrency } ?: return null }
                ?: 1
        if (pin.isNotEmpty() && (quic || concurrency != 1)) return null
        return Options(headers, pin, quic, concurrency)
    }

    fun encode(profile: ProxyProfile.Naive): String {
        val params =
            listOfNotNull(
                profile.extraHeaders.takeIf { it.isNotEmpty() }?.let { "extra-headers=${encode(it)}" },
                profile.serverPinSha256.takeIf { it.isNotEmpty() }?.let { "pinSHA256=${encode(it)}" },
                profile.concurrency.takeIf { it > 1 }?.let { "insecure-concurrency=$it" },
            )
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val server = profile.server
        val host = if (server.contains(':') && !server.startsWith("[")) "[$server]" else server
        val scheme = if (profile.quic) "naive+quic" else "naive+https"
        return "$scheme://${encode(profile.username)}:${encode(profile.password)}" +
            "@$host:${profile.serverPort}/$query#${encode(profile.displayName)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** RFC 3986 percent-decoding: `+` stays literal; null for a malformed escape. */
    private fun decode(value: String): String? = ProxyUriCodec.rfc3986PercentDecode(value)

    private inline fun queryValue(
        rawQuery: String?,
        key: String,
        malformed: () -> Nothing,
    ): String? =
        rawQuery
            ?.split('&')
            ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
            ?.firstOrNull { (name, _) -> name == key }
            ?.second
            ?.let { decode(it) ?: malformed() }
}
