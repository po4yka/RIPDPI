package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.normalizeHysteria2PinSha256
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

/**
 * Per-scheme proxy URI codec. Parses a single share-link / subscription-line
 * URI into a [ProxyProfile].
 *
 * Supported schemes: `vless://`, `ss://` (SIP002), `trojan://`,
 * `hysteria2://` / `hy2://`, `anytls://`, `tuic://`, `mieru://`, `ssh://`. An unrecognised scheme — or a
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
    private const val DEFAULT_TLS_PORT = 443

    /** RFC 3986 characters that java.net.URI accepts verbatim in a query or fragment, besides alphanumerics. */
    private const val URI_TAIL_ALLOWED = "-._~!$&'()*+,;=:@/?"
    private const val FIRST_NON_ASCII = '\u0080'

    /**
     * Parses [uri] into a [ProxyProfile], or returns `null` when the scheme is
     * unknown or the URI cannot be interpreted as a proxy node. Never throws.
     */
    @Suppress("ReturnCount")
    fun parse(uri: String): ProxyProfile? {
        val trimmed = escapeUnsafeQueryAndFragment(uri.trim())
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
                "ssh" -> parseSsh(trimmed)
                "tuic" -> parseTuic(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Encodes [profile] into a canonical share URI accepted by [parse]. Throws
     * [IllegalArgumentException] when [profile] has no URI representation.
     */
    fun encode(profile: ProxyProfile): String =
        encodeOrNull(profile)
            ?: throw IllegalArgumentException("Profile cannot be expressed as a share URI")

    /**
     * Encodes [profile] into a canonical share URI accepted by [parse], or returns
     * `null` for opaque raw configs that are not already URI-shaped.
     */
    fun encodeOrNull(profile: ProxyProfile): String? =
        when (profile) {
            is ProxyProfile.Vless -> {
                encodeVless(profile)
            }

            is ProxyProfile.VlessReality -> {
                encodeVlessReality(profile)
            }

            is ProxyProfile.Trojan -> {
                encodeTrojan(profile)
            }

            is ProxyProfile.Hysteria2 -> {
                encodeHysteria2(profile)
            }

            is ProxyProfile.AnyTls -> {
                encodeAnyTls(profile)
            }

            is ProxyProfile.Mieru -> {
                encodeMieru(profile)
            }

            is ProxyProfile.Ssh -> {
                encodeSsh(profile)
            }

            is ProxyProfile.Shadowsocks -> {
                encodeShadowsocks(profile)
            }

            is ProxyProfile.RawConfig -> {
                profile.config.takeIf { it.contains("://") }
            }
        }

    private fun userInfoUri(
        scheme: String,
        userInfo: String,
        host: String,
        port: Int,
        displayName: String,
    ): String = "$scheme://${encodeQueryValue(userInfo)}@${bracketIpv6(host)}:$port#${encodeFragment(displayName)}"

    private fun encodeVlessReality(profile: ProxyProfile.VlessReality): String {
        val params =
            buildList {
                add("security=reality")
                add("pbk=${encodeQueryValue(profile.realityPublicKey)}")
                if (profile.realityShortId.isNotEmpty()) add("sid=${encodeQueryValue(profile.realityShortId)}")
                add("sni=${encodeQueryValue(profile.serverName)}")
                add("flow=${encodeQueryValue(profile.flow)}")
                profile.fingerprint?.let { add("fp=${encodeQueryValue(it)}") }
                if (profile.xhttpPath != null || profile.xhttpHost != null) {
                    add("type=xhttp")
                    profile.xhttpPath?.let { add("path=${encodeQueryValue(it)}") }
                    profile.xhttpHost?.let { add("host=${encodeQueryValue(it)}") }
                    add("mode=${encodeQueryValue(profile.xhttpMode)}")
                }
            }.joinToString("&")
        return "vless://${profile.uuid}@${bracketIpv6(profile.server)}:${profile.serverPort}" +
            "?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeVless(profile: ProxyProfile.Vless): String {
        val params =
            buildList {
                add("security=tls")
                profile.serverName?.let { add("sni=${encodeQueryValue(it)}") }
                add("flow=${encodeQueryValue(profile.flow)}")
                profile.fingerprint?.let { add("fp=${encodeQueryValue(it)}") }
                if (profile.xhttpPath != null || profile.xhttpHost != null) {
                    add("type=xhttp")
                    profile.xhttpPath?.let { add("path=${encodeQueryValue(it)}") }
                    profile.xhttpHost?.let { add("host=${encodeQueryValue(it)}") }
                    add("mode=${encodeQueryValue(profile.xhttpMode)}")
                }
            }.joinToString("&")
        return "vless://${profile.uuid}@${bracketIpv6(profile.server)}:${profile.serverPort}" +
            "?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeMieru(profile: ProxyProfile.Mieru): String {
        val params =
            buildList {
                add("protocol=${encodeQueryValue(profile.protocol)}")
                add("mux=${encodeQueryValue(profile.multiplexing)}")
                add("mtu=${profile.mtu}")
            }.joinToString("&")
        return "mieru://${encodeQueryValue(profile.username)}:${encodeQueryValue(profile.password)}" +
            "@${bracketIpv6(profile.server)}:${profile.serverPort}?$params#${encodeFragment(profile.displayName)}"
    }

    /**
     * Encodes an SSH profile into the RIPDPI-invented `ssh://` share link. The form is
     * `ssh://<user>[:<password>]@<host>:<port>?auth=<type>[&key=&passphrase=&fp=&strict=1]#<name>`,
     * where `<type>` is `password` or `private_key`.
     *
     * Password-auth carries the password in the userinfo (mirroring Mieru);
     * private-key-auth leaves the userinfo password-less and percent-encodes the
     * multi-line PEM (and optional passphrase) into the query, since a PEM cannot
     * live in URI userinfo. All secret material is percent-encoded.
     */
    private fun encodeSsh(profile: ProxyProfile.Ssh): String {
        val params =
            buildList {
                add("auth=${encodeQueryValue(profile.authType)}")
                profile.privateKey?.let { add("key=${encodeQueryValue(it)}") }
                profile.privateKeyPassphrase?.let { add("passphrase=${encodeQueryValue(it)}") }
                profile.hostKeyFingerprint?.let { add("fp=${encodeQueryValue(it)}") }
                if (profile.strictHostKey) add("strict=1")
            }.joinToString("&")
        val userInfo =
            if (profile.password != null) {
                "${encodeQueryValue(profile.username)}:${encodeQueryValue(profile.password)}"
            } else {
                encodeQueryValue(profile.username)
            }
        return "ssh://$userInfo@${bracketIpv6(
            profile.server,
        )}:${profile.serverPort}?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeAnyTls(profile: ProxyProfile.AnyTls): String {
        val params = "?sni=${encodeQueryValue(profile.serverName)}"
        return "anytls://${encodeQueryValue(profile.password)}@${bracketIpv6(
            profile.server,
        )}:${profile.serverPort}$params#${encodeFragment(
            profile.displayName,
        )}"
    }

    private fun encodeTrojan(profile: ProxyProfile.Trojan): String {
        val base = userInfoUri("trojan", profile.password, profile.server, profile.serverPort, profile.displayName)
        val sni = profile.serverName ?: return base
        // Insert ?sni=... before the #fragment so parse() round-trips serverName.
        val hashIndex = base.indexOf('#')
        return base.substring(0, hashIndex) + "?sni=${encodeQueryValue(sni)}" + base.substring(hashIndex)
    }

    private fun encodeHysteria2(profile: ProxyProfile.Hysteria2): String {
        val ports = profile.portHopping?.ports ?: profile.serverPort.toString()
        val auth = encodeQueryValue(profile.password).replace("+", "%20")
        val base = "hysteria2://$auth@${bracketIpv6(profile.server)}:$ports#${encodeFragment(profile.displayName)}"
        val params =
            buildList {
                profile.serverName?.let { add("sni=${encodeQueryValue(it)}") }
                profile.obfsPassword?.let {
                    add("obfs=salamander")
                    add("obfs-password=${encodeQueryValue(it)}")
                }
                if (profile.insecure == true) add("insecure=1")
                profile.pinSha256?.let { add("pinSHA256=$it") }
                profile.portHopping?.let { hopping ->
                    if (hopping.intervalMaxMillis == null) {
                        add("hopInterval=${hopping.intervalMillis}ms")
                    } else {
                        add("minHopInterval=${hopping.intervalMillis}ms")
                        add("maxHopInterval=${hopping.intervalMaxMillis}ms")
                    }
                }
            }
        if (params.isEmpty()) return base
        val hashIndex = base.indexOf('#')
        return base.substring(0, hashIndex) + "?" + params.joinToString("&") + base.substring(hashIndex)
    }

    private fun encodeShadowsocks(profile: ProxyProfile.Shadowsocks): String {
        val userInfo = "${profile.method}:${profile.password}"
        val encoded =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(userInfo.toByteArray(Charsets.UTF_8))
        return "ss://$encoded@${bracketIpv6(
            profile.server,
        )}:${profile.serverPort}#${encodeFragment(profile.displayName)}"
    }

    private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodeFragment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Suppress("ReturnCount")
    private fun parseVless(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val uuid = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        if (!supportsNativeVlessUriOptions(rawQuery)) return null
        // Detect REALITY only when usable key material is present. A bare
        // security=reality flag without pbk cannot complete a REALITY handshake.
        val security = queryValue(rawQuery, "security")
        val pbk = queryValue(rawQuery, "pbk")
        val isReality = security?.lowercase() == "reality" || !pbk.isNullOrBlank()
        return if (isReality) {
            parseVlessReality(parsed, uuid, host, port, rawQuery, pbk)
        } else {
            parsePlainVless(parsed, uuid, host, port, rawQuery)
        }
    }

    private fun parseVlessReality(
        parsed: URI,
        uuid: String,
        host: String,
        port: Int,
        rawQuery: String?,
        publicKey: String?,
    ): ProxyProfile.VlessReality? {
        val realityPublicKey = publicKey?.takeIf(String::isNotBlank)
        val transportType = queryValue(rawQuery, "type")?.lowercase()
        // RIPDPI's VLESS+REALITY client only implements plain TCP and xhttp.
        val supportedTransport = transportType == null || transportType in setOf("tcp", "xhttp")
        return if (realityPublicKey != null && supportedTransport) {
            val isXhttp = transportType == "xhttp"
            // XTLS Vision runs only over raw TLS/REALITY streams; xHTTP links omit `flow`.
            val defaultFlow = if (isXhttp) "" else "xtls-rprx-vision"
            ProxyProfile.VlessReality(
                id = newId(),
                displayName = displayName(parsed.fragment, host),
                groupId = "",
                server = host,
                serverPort = port,
                uuid = uuid,
                realityPublicKey = realityPublicKey,
                realityShortId = queryValue(rawQuery, "sid").orEmpty(),
                serverName = queryValue(rawQuery, "sni") ?: host,
                flow = queryValue(rawQuery, "flow") ?: defaultFlow,
                fingerprint = queryValue(rawQuery, "fp"),
                xhttpPath = if (isXhttp) queryValue(rawQuery, "path").orEmpty() else null,
                xhttpHost = if (isXhttp) queryValue(rawQuery, "host") else null,
                xhttpMode = if (isXhttp) queryValue(rawQuery, "mode") ?: "auto" else "auto",
            )
        } else {
            null
        }
    }

    private fun parsePlainVless(
        parsed: URI,
        uuid: String,
        host: String,
        port: Int,
        rawQuery: String?,
    ): ProxyProfile.Vless? {
        // The plain VLESS backend is TLS with raw TCP or xHTTP and no VLESS encryption. Any other
        // transport, header obfuscation or security would import as a node that never connects.
        val transportType = queryValue(rawQuery, "type")?.lowercase()
        val supported =
            transportType in setOf(null, "tcp", "raw", "xhttp") &&
                queryValue(rawQuery, "encryption")?.lowercase() in setOf(null, "", "none") &&
                queryValue(rawQuery, "security")?.lowercase() in setOf(null, "tls") &&
                queryValue(rawQuery, "headerType")?.lowercase() in setOf(null, "none")
        if (!supported) return null
        val isXhttp = transportType == "xhttp"
        return ProxyProfile.Vless(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            uuid = uuid,
            serverName = queryValue(rawQuery, "sni") ?: queryValue(rawQuery, "serverName"),
            flow = queryValue(rawQuery, "flow").orEmpty(),
            fingerprint = queryValue(rawQuery, "fp"),
            xhttpPath = if (isXhttp) queryValue(rawQuery, "path").orEmpty() else null,
            xhttpHost = if (isXhttp) queryValue(rawQuery, "host") else null,
            xhttpMode = queryValue(rawQuery, "mode") ?: "auto",
        )
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
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
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

    /**
     * Parses the RIPDPI-invented `ssh://` share link into a [ProxyProfile.Ssh].
     * See [encodeSsh] for the canonical form. The `auth` query param selects the
     * auth type; for password-auth the password is in the userinfo, for
     * private-key-auth the percent-encoded PEM (and optional passphrase) are in
     * the query. Returns `null` for a structurally invalid node.
     */
    @Suppress("ReturnCount")
    private fun parseSsh(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val rawUserInfo = parsed.rawUserInfo?.takeIf { it.isNotBlank() } ?: return null
        val separator = rawUserInfo.indexOf(':')
        val username: String
        val passwordFromUserInfo: String?
        if (separator < 0) {
            username = percentDecode(rawUserInfo).takeIf { it.isNotBlank() } ?: return null
            passwordFromUserInfo = null
        } else {
            username = percentDecode(rawUserInfo.substring(0, separator)).takeIf { it.isNotBlank() } ?: return null
            passwordFromUserInfo = percentDecode(rawUserInfo.substring(separator + 1)).takeIf { it.isNotBlank() }
        }
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        val strict = queryValue(rawQuery, "strict")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        return ProxyProfile.Ssh(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            username = username,
            authType = normalizeSshAuthType(queryValue(rawQuery, "auth")),
            password = passwordFromUserInfo,
            privateKey = queryValue(rawQuery, "key"),
            privateKeyPassphrase = queryValue(rawQuery, "passphrase"),
            hostKeyFingerprint = queryValue(rawQuery, "fp"),
            strictHostKey = strict,
        )
    }

    /** Maps the `auth` query token to the native ripdpi-ssh selector; defaults to `password`. */
    private fun normalizeSshAuthType(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "private_key", "privatekey", "key" -> "private_key"
            else -> "password"
        }

    private fun percentDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** RFC 3986 percent-decoding: '+' stays literal. Returns null for a malformed escape. */
    private fun rfc3986PercentDecode(value: String): String? =
        runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()

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
        val (rawHost, port) = splitHostPort(hostPort) ?: return null
        val host = unbracketIpv6(rawHost)

        val plainUserInfo = userInfoRaw.contains(':')
        val userInfo =
            if (plainUserInfo) {
                userInfoRaw
            } else {
                decodeBase64(userInfoRaw) ?: return null
            }
        val methodSep = userInfo.indexOf(':')
        if (methodSep <= 0) return null
        // SIP002: plain-form method and password are percent-encoded (SIP022 keys carry %2B, %2F, %3D).
        val decodePart: (String) -> String? = { if (plainUserInfo) rfc3986PercentDecode(it) else it }
        val method = decodePart(userInfo.substring(0, methodSep)) ?: return null
        val password = decodePart(userInfo.substring(methodSep + 1)) ?: return null
        if (!isSupportedShadowsocksMethod(method)) return null
        if (password.isEmpty()) return null
        // SIP003 `plugin=` (obfs-local / v2ray-plugin) needs an out-of-process
        // transport RIPDPI does not bundle. Importing such a node as plain ss would
        // build a profile that silently fails to connect, so reject it loudly and
        // let the import surface surface the error.
        val query = core.substring(atIndex + 1).substringAfter('?', "")
        if (queryValue(query, "plugin") != null) return null
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

    /** Shared with the subscription parsers so every import path gates the same cipher allowlist. */
    internal fun isSupportedShadowsocksMethod(method: String): Boolean =
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
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        // RIPDPI's trojan backend is TLS-only; it has no WebSocket / gRPC / HTTP2
        // transport. A node advertising type=ws/grpc/h2 would import as plain TLS
        // and silently fail to connect, so reject it loudly instead of dropping the
        // transport.
        when (queryValue(rawQuery, "type")?.lowercase()) {
            null, "tcp", "original", "none" -> Unit
            else -> return null
        }
        return ProxyProfile.Trojan(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            password = password,
            serverName = queryValue(rawQuery, "sni") ?: queryValue(rawQuery, "serverName"),
        )
    }

    @Suppress("ReturnCount")
    private fun parseHysteria2(uri: String): ProxyProfile? {
        val authority = parseHysteria2UriAuthority(uri)
        val parsed = authority.uri
        val password = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val rawQuery = parsed.rawQuery
        val obfsType = queryValue(rawQuery, "obfs")
        if (obfsType != null && !obfsType.equals("salamander", ignoreCase = true)) return null
        // REL-3: pinSHA256 is the certificate pin that makes insecure=1 safe for a
        // self-signed server. A malformed pin rejects the link: if the importer
        // dropped it and kept insecure=1, the profile would accept any certificate.
        val rawPin = queryValue(rawQuery, "pinSHA256", ignoreCase = true)?.takeIf { it.isNotBlank() }
        val pinSha256 = rawPin?.let { normalizeHysteria2PinSha256(it) ?: return null }
        return ProxyProfile.Hysteria2(
            id = newId(),
            displayName = displayName(parsed.rawFragment, host),
            groupId = "",
            server = host,
            serverPort = port,
            password = password,
            serverName = queryValue(rawQuery, "sni") ?: queryValue(rawQuery, "serverName"),
            // Salamander obfuscation password. The activator maps obfsPassword ->
            // hysteriaSalamanderKey, which the native QUIC backend already honours;
            // dropping it silently disabled the configured censorship-resistance.
            obfsPassword = queryValue(rawQuery, "obfs-password"),
            // insecure=1 skips TLS cert verification; dropping it silently forced
            // strict verification and an opaque handshake failure (P1-9).
            insecure = queryValue(rawQuery, "insecure")?.let { it == "1" || it.equals("true", ignoreCase = true) },
            pinSha256 = pinSha256,
            portHopping = authority.portHopping,
        )
    }

    @Suppress("ReturnCount")
    private fun parseAnyTls(uri: String): ProxyProfile? {
        val parsed = URI(uri)
        val password = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
        // anytls-go uri_scheme.md: the port defaults to 443.
        val port = if (parsed.port == -1) DEFAULT_TLS_PORT else parsed.port.takeIf { it > 0 } ?: return null
        // The AnyTLS client always verifies the server certificate and has no pin option, so a
        // link that asks to skip verification would import a profile that can never connect.
        val insecure = queryValue(parsed.rawQuery, "insecure")
        if (insecure == "1" || insecure.equals("true", ignoreCase = true)) return null
        // RIPDPI's AnyTLS client has no server-side TLS-fallback support (upstream
        // anytls-go fallback is a server-only knob). Reject a node that advertises
        // a fallback target explicitly rather than silently importing a profile that
        // would behave differently than the share link implies.
        if (queryValue(parsed.rawQuery, "fallback") != null ||
            queryValue(parsed.rawQuery, "fallback_sni") != null ||
            queryValue(parsed.rawQuery, "fallbackSni") != null
        ) {
            return null
        }
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
        val host = parsed.host?.takeIf { it.isNotBlank() }?.let(::unbracketIpv6) ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        if (parsed.userInfo.isNullOrBlank()) return null
        return ProxyProfile.RawConfig(
            id = newId(),
            displayName = displayName(parsed.fragment, host),
            groupId = "",
            config = uri,
        )
    }

    /**
     * Strips the surrounding brackets that [java.net.URI.getHost] keeps on IPv6
     * literals (e.g. `[2001:db8::1]` -> `2001:db8::1`). The native connect path
     * (`resolve_server_addr`) parses the stored `server` with `IpAddr::parse`,
     * which rejects bracketed literals and would fall through to DNS and never
     * resolve. Hostnames and IPv4 literals have no surrounding brackets, so this
     * is a no-op for them.
     */
    private fun unbracketIpv6(host: String): String = host.removeSurrounding("[", "]")

    /**
     * Re-adds the brackets an IPv6 literal needs to be unambiguous inside a
     * `host:port` URI authority. Symmetric with [unbracketIpv6] so that
     * `parse(encode(profile)) == profile`. Only brackets a bare IPv6 (contains
     * `:` and is not already bracketed); a no-op for hostnames and IPv4.
     */
    private fun bracketIpv6(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

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
        ignoreCase: Boolean = false,
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
            }?.firstOrNull { (name, _) -> name.equals(key, ignoreCase = ignoreCase) }
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

    /**
     * Percent-encodes the characters that java.net.URI rejects in the query and fragment (spaces,
     * `|`, `{`, a stray `%`, a second `#`). Subscription emitters often leave remarks unencoded;
     * valid escapes and the authority are left unchanged.
     */
    private fun escapeUnsafeQueryAndFragment(uri: String): String {
        val fragmentStart = uri.indexOf('#')
        val queryStart = uri.indexOf('?').takeIf { it >= 0 && (fragmentStart < 0 || it < fragmentStart) }
        val tailStart = queryStart ?: fragmentStart.takeIf { it >= 0 } ?: return uri
        val escaped = StringBuilder(uri.length).append(uri, 0, tailStart + 1)
        for (index in tailStart + 1 until uri.length) {
            val char = uri[index]
            val keep =
                index == fragmentStart ||
                    (char < FIRST_NON_ASCII && char.isLetterOrDigit()) ||
                    char in URI_TAIL_ALLOWED ||
                    (char >= FIRST_NON_ASCII && !char.isWhitespace() && !char.isISOControl()) ||
                    (char == '%' && isPercentEscape(uri, index))
            if (keep) {
                escaped.append(char)
            } else {
                char.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                    // %X formats a negative Byte as its unsigned value.
                    escaped.append("%%%02X".format(byte))
                }
            }
        }
        return escaped.toString()
    }

    private fun isPercentEscape(
        value: String,
        index: Int,
    ): Boolean =
        index + 2 < value.length &&
            value[index + 1].isHexDigitChar() &&
            value[index + 2].isHexDigitChar()

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun newId(): String = UUID.randomUUID().toString()
}
