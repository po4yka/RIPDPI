package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * Per-scheme proxy URI codec. Parses a single share-link / subscription-line
 * URI into a [ProxyProfile].
 *
 * Supported schemes: `vless://`, `vmess://`, `ss://` (SIP002), `trojan://`,
 * `hysteria2://` / `hy2://`, `anytls://`, `tuic://`. An unrecognised scheme — or a
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

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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
                "vmess" -> parseVmess(trimmed)
                "ss" -> parseShadowsocks(trimmed)
                "trojan-go" -> parseTrojanGo(trimmed)
                "mieru" -> parseMieru(trimmed)
                "trojan" -> parseTrojan(trimmed)
                "hysteria" -> parseHysteriaV1(trimmed)
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
     * Parses a VMess share link into a [ProxyProfile.Vmess]. Two encodings are
     * accepted:
     *  - the Clash / v2rayN form `vmess://<base64 of a JSON object>` (`add`,
     *    `port`, `id`, `ps`, `net`, `path`, `host`, plus the legacy `scy`/`type`
     *    cipher hints);
     *  - the standard URI form `vmess://<uuid>@<host>:<port>?security=…&type=…`.
     *
     * Both map to the first-class [ProxyProfile.Vmess] type (never `Vless`). The
     * deprecated `alterId` is intentionally ignored — RIPDPI only speaks the
     * AEAD (`alterId == 0`) handshake.
     */
    @Suppress("ReturnCount")
    private fun parseVmess(uri: String): ProxyProfile? {
        val body = uri.removePrefix("vmess://").trim()
        val decoded = decodeBase64(body)
        if (decoded != null) {
            val obj = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            if (obj != null) return parseVmessJson(obj)
        }
        return parseVmessStandardUri(uri)
    }

    @Suppress("ReturnCount")
    private fun parseVmessJson(obj: JsonObject): ProxyProfile? {
        val host = obj.stringOf("add") ?: return null
        val port = obj.intOf("port") ?: return null
        val id = obj.stringOf("id") ?: return null
        val name = obj.stringOf("ps")?.takeIf { it.isNotBlank() } ?: host
        val transport = normalizeVmessTransport(obj.stringOf("net"))
        val path = obj.stringOf("path")
        val hostHeader = obj.stringOf("host")
        return ProxyProfile.Vmess(
            id = newId(),
            displayName = name,
            groupId = "",
            server = host,
            serverPort = port,
            uuid = id,
            security = normalizeVmessSecurity(obj.stringOf("scy")),
            transport = transport,
            wsPath = path.takeIf { transport == "ws" },
            wsHost = hostHeader.takeIf { transport == "ws" },
            h2Path = path.takeIf { transport == "h2" },
            h2Host = hostHeader.takeIf { transport == "h2" },
            grpcService = obj.stringOf("path").takeIf { transport == "grpc" },
        )
    }

    @Suppress("ReturnCount")
    private fun parseVmessStandardUri(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val uuid = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        val transport = normalizeVmessTransport(queryValue(rawQuery, "type"))
        val path = queryValue(rawQuery, "path")
        val hostHeader = queryValue(rawQuery, "host")
        return ProxyProfile.Vmess(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            uuid = uuid,
            security = normalizeVmessSecurity(queryValue(rawQuery, "security")),
            transport = transport,
            wsPath = path.takeIf { transport == "ws" },
            wsHost = hostHeader.takeIf { transport == "ws" },
            h2Path = path.takeIf { transport == "h2" },
            h2Host = hostHeader.takeIf { transport == "h2" },
            grpcService = (queryValue(rawQuery, "serviceName") ?: path).takeIf { transport == "grpc" },
        )
    }

    private fun normalizeVmessSecurity(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "chacha20-poly1305" -> "chacha20-poly1305"
            else -> "aes-128-gcm"
        }

    private fun normalizeVmessTransport(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "ws", "websocket" -> "ws"
            "h2", "http" -> "h2"
            "grpc" -> "grpc"
            else -> "tcp"
        }

    /**
     * Parses a Trojan-Go share link into a [ProxyProfile.TrojanGo]. The standard
     * form is `trojan-go://<password>@<host>:<port>?sni=…&type=ws&path=…&host=…&mux=…&encryption=ss;<cipher>;<pass>#<name>`.
     *
     * Only the WebSocket transport carries `path` / `host`; the `mux` query maps
     * to `off` / `smux_v1`; the `encryption` query's middle token (the Shadowsocks
     * cipher) maps to the optional inner cipher. Unknown values fall back to the
     * canonical defaults (`off` mux, `none` inner cipher).
     */
    @Suppress("ReturnCount")
    private fun parseTrojanGo(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val password = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        val transport = queryValue(rawQuery, "type")?.trim()?.lowercase()
        val isWs = transport == "ws" || transport == "websocket"
        val path = queryValue(rawQuery, "path")
        val hostHeader = queryValue(rawQuery, "host")
        return ProxyProfile.TrojanGo(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            password = password,
            sni = queryValue(rawQuery, "sni")?.takeIf { it.isNotBlank() },
            wsPath = path.takeIf { isWs },
            wsHost = hostHeader.takeIf { isWs },
            mux = normalizeTrojanGoMux(queryValue(rawQuery, "mux")),
            innerCipher = normalizeTrojanGoInnerCipher(queryValue(rawQuery, "encryption")),
        )
    }

    private fun normalizeTrojanGoMux(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "smux_v1", "smux", "1", "true", "on" -> "smux_v1"
            else -> "off"
        }

    /**
     * Normalizes a Trojan-Go `encryption` query value. Accepts both the bare
     * cipher name and the `ss;<cipher>;<password>` Shadowsocks-AEAD inner form,
     * keying only on the cipher token.
     */
    private fun normalizeTrojanGoInnerCipher(value: String?): String {
        val token =
            value
                ?.trim()
                ?.split(';')
                ?.getOrNull(1)
                ?.trim()
                ?.ifBlank { null }
                ?: value?.trim()?.takeIf { !it.contains(';') }
        return when (token?.lowercase()) {
            "aes-256-gcm" -> "aes-256-gcm"
            "chacha20-ietf-poly1305" -> "chacha20-ietf-poly1305"
            else -> "none"
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
        val parsed = value?.trim()?.toIntOrNull() ?: return 1400
        return if (parsed in 1280..1500) parsed else 1400
    }

    /**
     * Parses a legacy Hysteria v1 share link into a [ProxyProfile.HysteriaV1]. The
     * form is
     * `hysteria://<host>:<port>?auth=...&protocol=udp&obfs=...&upmbps=10&downmbps=50&peer=<sni>&alpn=...`.
     *
     * `auth` and `obfs` are percent-encoded in the query and decoded here (base64
     * auth payloads commonly contain `+`, `/`, and `=`). The Hysteria v1 URI form
     * has no canonical slot for the auth-type encoding, so it defaults to `string`
     * on import; an explicit `authtype` query key is honoured when present. Only
     * the bare `hysteria://` scheme reaches here — `hysteria2://` and `hy2://` are
     * routed to the active Hysteria2 parser.
     */
    @Suppress("ReturnCount")
    private fun parseHysteriaV1(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        val authPayload = queryValue(rawQuery, "auth")?.takeIf { it.isNotBlank() } ?: return null
        val obfuscation = queryValue(rawQuery, "obfs")?.takeIf { it.isNotBlank() }
        val sni = queryValue(rawQuery, "peer")?.takeIf { it.isNotBlank() }
        val alpn = queryValue(rawQuery, "alpn")?.takeIf { it.isNotBlank() }
        return ProxyProfile.HysteriaV1(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            authType = normalizeHysteriaV1AuthType(queryValue(rawQuery, "authtype")),
            authPayload = authPayload,
            obfuscation = obfuscation,
            protocol = normalizeHysteriaV1Protocol(queryValue(rawQuery, "protocol")),
            upMbps = normalizeHysteriaV1Mbps(queryValue(rawQuery, "upmbps"), 10),
            downMbps = normalizeHysteriaV1Mbps(queryValue(rawQuery, "downmbps"), 50),
            sni = sni,
            alpn = alpn,
        )
    }

    private fun normalizeHysteriaV1AuthType(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "base64" -> "base64"
            else -> "string"
        }

    private fun normalizeHysteriaV1Protocol(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "wechat-video" -> "wechat-video"
            "faketcp" -> "faketcp"
            else -> "udp"
        }

    private fun normalizeHysteriaV1Mbps(
        value: String?,
        fallback: Int,
    ): Int {
        val parsed = value?.trim()?.toIntOrNull() ?: return fallback
        return if (parsed > 0) parsed else fallback
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

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOf(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }
}
