package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * Per-scheme proxy URI codec. Parses a single share-link / subscription-line
 * URI into a [ProxyProfile].
 *
 * Supported schemes: `vless://`, `ss://` (SIP002), `trojan://`,
 * `hysteria2://` / `hy2://`, `anytls://`, `tuic://`, `mieru://`. An unrecognised scheme — or a
 * structurally malformed URI of a known scheme — yields `null` so callers can
 * skip the line.
 *
 * Ported from NekoBox's per-protocol `*Fmt.kt` `parseXxx(url)` functions; the
 * Kryo serialization round-trip is intentionally dropped — this codec goes
 * straight to the [ProxyProfile] sealed type.
 *
 * `TooManyFunctions` is suppressed: each `parseXxx` handles one share-link
 * scheme, so the function count tracks the supported-scheme count by design.
 */
@Suppress("TooManyFunctions")
object ProxyUriCodec {
    /** Base64 encodes 3 input bytes per 4 output chars, so encoded length is always a multiple of 4. */
    private const val BASE64_GROUP_SIZE = 4
    private const val MIERU_MTU_MIN = 1280
    private const val MIERU_MTU_MAX = 1500
    private const val MIERU_MTU_DEFAULT = 1400

    /**
     * Parses [uri] into a [ProxyProfile], or returns `null` when the scheme is
     * unknown or the URI cannot be interpreted as a proxy node. Never throws.
     */
    @Suppress("ReturnCount")
    fun parse(uri: String): ProxyProfile? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return null
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        return runCatching {
            when (scheme) {
                "vless" -> parseVless(trimmed)
                "ss" -> parseShadowsocks(trimmed)
                "mieru" -> parseMieru(trimmed)
                "trojan" -> parseTrojan(trimmed)
                "hysteria2", "hy2" -> parseHysteria2(trimmed)
                "anytls" -> parseAnyTls(trimmed)
                "tuic" -> parseTuic(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    @Suppress("ReturnCount")
    private fun parseVless(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val uuid = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        // Detect REALITY: security=reality query param, OR a non-empty pbk param.
        val security = queryValue(rawQuery, "security")
        val pbk = queryValue(rawQuery, "pbk")
        val isReality = security?.lowercase() == "reality" || !pbk.isNullOrBlank()
        return if (isReality) {
            val sid = queryValue(rawQuery, "sid").orEmpty()
            val sni = queryValue(rawQuery, "sni") ?: host
            val flow = queryValue(rawQuery, "flow") ?: "xtls-rprx-vision"
            val fp = queryValue(rawQuery, "fp")
            val transportType = queryValue(rawQuery, "type")?.lowercase()
            val xhttpPath = if (transportType == "xhttp") queryValue(rawQuery, "path") else null
            val xhttpHost = if (transportType == "xhttp") queryValue(rawQuery, "host") else null
            ProxyProfile.VlessReality(
                id = newId(),
                displayName = displayName(parsed.fragment, host),
                groupId = "",
                server = host,
                serverPort = port,
                uuid = uuid,
                realityPublicKey = pbk.orEmpty(),
                realityShortId = sid,
                serverName = sni,
                flow = flow,
                fingerprint = fp,
                xhttpPath = xhttpPath,
                xhttpHost = xhttpHost,
            )
        } else {
            ProxyProfile.Vless(
                id = newId(),
                displayName = displayName(parsed.fragment, host),
                groupId = "",
                server = host,
                serverPort = port,
                uuid = uuid,
            )
        }
    }

    /**
     * Parses an invented Mieru share link into a [ProxyProfile.Mieru]. The form is
     * `mieru://<username>:<password>@<host>:<port>?protocol=tcp&mux=middle&mtu=1400#<name>`.
     *
     * Both the username and the password are percent-encoded in the userinfo and
     * are decoded here. When the query keys are absent the canonical Mieru
     * defaults apply: `protocol=tcp`, `mux=middle`, `mtu=1400`. Note the URI query
     * key is `mux` while the profile field is `multiplexing`.
     */
    @Suppress("ReturnCount")
    private fun parseMieru(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val rawUserInfo = parsed.rawUserInfo?.takeIf { it.isNotBlank() } ?: return null
        val separator = rawUserInfo.indexOf(':')
        if (separator <= 0 || separator >= rawUserInfo.length - 1) return null
        val username = percentDecode(rawUserInfo.substring(0, separator)).takeIf { it.isNotBlank() } ?: return null
        val password = percentDecode(rawUserInfo.substring(separator + 1)).takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        return ProxyProfile.Mieru(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            username = username,
            password = password,
            protocol = normalizeMieruProtocol(queryValue(rawQuery, "protocol")),
            multiplexing = normalizeMieruMultiplexing(queryValue(rawQuery, "mux")),
            mtu = normalizeMieruMtu(queryValue(rawQuery, "mtu")),
        )
    }

    private fun percentDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun normalizeMieruProtocol(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "udp" -> "udp"
            else -> "tcp"
        }

    private fun normalizeMieruMultiplexing(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "off" -> "off"
            "low" -> "low"
            "high" -> "high"
            else -> "middle"
        }

    private fun normalizeMieruMtu(value: String?): Int {
        val parsed = value?.trim()?.toIntOrNull() ?: return MIERU_MTU_DEFAULT
        return if (parsed in MIERU_MTU_MIN..MIERU_MTU_MAX) parsed else MIERU_MTU_DEFAULT
    }

    @Suppress("ReturnCount")
    private fun parseShadowsocks(uri: String): ProxyProfile? {
        // SIP002: ss://base64(method:password)@host:port#tag
        //   or    ss://method:password@host:port#tag (plain userinfo)
        val body = uri.removePrefix("ss://")
        val fragmentIndex = body.indexOf('#')
        val fragment = if (fragmentIndex >= 0) body.substring(fragmentIndex + 1) else null
        val core = if (fragmentIndex >= 0) body.substring(0, fragmentIndex) else body
        val atIndex = core.lastIndexOf('@')
        if (atIndex <= 0) return null
        val userInfoRaw = core.substring(0, atIndex)
        val hostPort = core.substring(atIndex + 1).substringBefore('?').substringBefore('/')
        val (host, port) = splitHostPort(hostPort) ?: return null

        val userInfo =
            if (userInfoRaw.contains(':')) {
                userInfoRaw
            } else {
                decodeBase64(userInfoRaw) ?: return null
            }
        val methodSep = userInfo.indexOf(':')
        if (methodSep <= 0) return null
        val method = userInfo.substring(0, methodSep)
        val password = userInfo.substring(methodSep + 1)
        if (!isSupportedShadowsocksMethod(method)) return null
        if (password.isEmpty()) return null
        return ProxyProfile.Shadowsocks(
            id = newId(),
            displayName = displayName(fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            method = method,
            password = password,
        )
    }

    private fun isSupportedShadowsocksMethod(method: String): Boolean =
        when (method.trim().lowercase()) {
            "aes-128-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
            -> true

            else -> false
        }

    @Suppress("ReturnCount")
    private fun parseTrojan(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val password = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        return ProxyProfile.Trojan(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            password = password,
        )
    }

    @Suppress("ReturnCount")
    private fun parseHysteria2(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val password = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        return ProxyProfile.Hysteria2(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            password = password,
        )
    }

    @Suppress("ReturnCount")
    private fun parseAnyTls(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val password = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        return ProxyProfile.AnyTls(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            serverName = queryValue(parsed.rawQuery, "sni") ?: queryValue(parsed.rawQuery, "serverName") ?: host,
            password = password,
        )
    }

    @Suppress("ReturnCount")
    private fun parseTuic(uri: String): ProxyProfile? {
        // TUIC has no first-class ProxyProfile subtype; round-trip as RawConfig
        // while still validating it is a structurally usable node URI.
        val parsed = URI(uri)
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        if (parsed.userInfo.isNullOrBlank()) return null
        return ProxyProfile.RawConfig(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            config = uri,
        )
    }

    @Suppress("ReturnCount")
    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        val sep = hostPort.lastIndexOf(':')
        if (sep <= 0) return null
        val host = hostPort.substring(0, sep).takeIf { it.isNotBlank() } ?: return null
        val port = hostPort.substring(sep + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
        return host to port
    }

    private fun displayName(
        fragment: String?,
        host: String,
    ): String {
        val decoded = fragment?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: it }
        return decoded?.takeIf { it.isNotBlank() } ?: host
    }

    private fun queryValue(
        rawQuery: String?,
        key: String,
    ): String? =
        rawQuery
            ?.split('&')
            ?.asSequence()
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    part.substring(0, separator) to part.substring(separator + 1)
                }
            }?.firstOrNull { (name, value) -> name == key && value.isNotBlank() }
            ?.second
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    @Suppress("ReturnCount")
    private fun decodeBase64(raw: String): String? {
        val cleaned = raw.trim().replace("\n", "").replace("\r", "")
        if (cleaned.isEmpty()) return null
        val candidates =
            listOf(
                Base64.getUrlDecoder(),
                Base64.getDecoder(),
            )
        val padded = padBase64(cleaned)
        for (decoder in candidates) {
            val result = runCatching { String(decoder.decode(padded)) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun padBase64(value: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        val remainder = normalized.length % BASE64_GROUP_SIZE
        return if (remainder == 0) {
            normalized
        } else {
            normalized + "=".repeat(BASE64_GROUP_SIZE - remainder)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
