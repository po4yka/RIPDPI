package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import java.net.URLEncoder
import java.util.Base64

/**
 * Offline encoder that turns a saved [ProxyProfile] back into a canonical per-protocol
 * share URI. This is the inverse of [ProxyUriCodec]: encode -> parse preserves the
 * endpoint identity for every first-class protocol.
 *
 * Encoding is purely local string assembly — no network round-trip. Only canonical
 * per-protocol schemes are emitted (`vless://`, `ss://`, `trojan://`, `hysteria2://`);
 * no `sn://`-style universal link is ever invented. A [ProxyProfile.RawConfig] is emitted
 * verbatim when it already wraps a URI, otherwise it cannot be expressed as a share URI.
 */
object ProxyProfileUriEncoder {
    /**
     * Encodes [profile] into a canonical share URI. Throws [IllegalArgumentException] for
     * a [ProxyProfile.RawConfig] whose payload is not itself a URI — callers that need a
     * soft failure should use [encodeOrNull].
     */
    fun encode(profile: ProxyProfile): String =
        encodeOrNull(profile)
            ?: throw IllegalArgumentException("Profile cannot be expressed as a share URI")

    /**
     * Encodes [profile] into a canonical share URI, or returns `null` when the profile
     * cannot be represented as one (a [ProxyProfile.RawConfig] holding an opaque,
     * non-URI config).
     */
    fun encodeOrNull(profile: ProxyProfile): String? =
        when (profile) {
            is ProxyProfile.Vless -> {
                userInfoUri("vless", profile.uuid, profile.server, profile.serverPort, profile.displayName)
            }

            is ProxyProfile.VlessReality -> {
                encodeVlessReality(profile)
            }

            is ProxyProfile.Vmess -> {
                encodeVmess(profile)
            }

            is ProxyProfile.Trojan -> {
                userInfoUri("trojan", profile.password, profile.server, profile.serverPort, profile.displayName)
            }

            is ProxyProfile.Hysteria2 -> {
                userInfoUri("hysteria2", profile.password, profile.server, profile.serverPort, profile.displayName)
            }

            is ProxyProfile.AnyTls -> {
                userInfoUri("anytls", profile.password, profile.server, profile.serverPort, profile.displayName)
            }

            is ProxyProfile.TrojanGo -> {
                encodeTrojanGo(profile)
            }

            is ProxyProfile.Mieru -> {
                encodeMieru(profile)
            }

            is ProxyProfile.HysteriaV1 -> {
                encodeHysteriaV1(profile)
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
    ): String = "$scheme://$userInfo@$host:$port#${encodeFragment(displayName)}"

    private fun encodeVlessReality(profile: ProxyProfile.VlessReality): String {
        // Produce: vless://<uuid>@<host>:<port>?security=reality&pbk=<key>&sid=<id>
        //   &sni=<sni>&flow=<flow>[&fp=<fp>]#<name>
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
                }
            }.joinToString("&")
        return "vless://${profile.uuid}@${profile.server}:${profile.serverPort}" +
            "?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeVmess(profile: ProxyProfile.Vmess): String {
        // Emit the standard URI form (round-trips through ProxyUriCodec's
        // standard-URI branch): vmess://<uuid>@<host>:<port>?security=…&type=…
        //   [&path=…&host=…|&serviceName=…]#<name>
        val params =
            buildList {
                add("security=${encodeQueryValue(profile.security)}")
                add("type=${encodeQueryValue(profile.transport)}")
                when (profile.transport) {
                    "ws" -> {
                        profile.wsPath?.let { add("path=${encodeQueryValue(it)}") }
                        profile.wsHost?.let { add("host=${encodeQueryValue(it)}") }
                    }

                    "h2" -> {
                        profile.h2Path?.let { add("path=${encodeQueryValue(it)}") }
                        profile.h2Host?.let { add("host=${encodeQueryValue(it)}") }
                    }

                    "grpc" -> {
                        profile.grpcService?.let { add("serviceName=${encodeQueryValue(it)}") }
                    }
                }
            }.joinToString("&")
        return "vmess://${profile.uuid}@${profile.server}:${profile.serverPort}" +
            "?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeTrojanGo(profile: ProxyProfile.TrojanGo): String {
        // Emit the standard Trojan-Go URI form (round-trips through
        // ProxyUriCodec.parseTrojanGo): trojan-go://<password>@<host>:<port>
        //   ?sni=…&type=ws&path=…&host=…&mux=smux_v1&encryption=ss;<cipher>;<pass>#<name>
        // Only WS carries path/host; the no-mux default and plain inner protocol
        // are omitted so they round-trip back to the canonical "off" / "none".
        val params =
            buildList {
                profile.sni?.takeIf { it.isNotBlank() }?.let { add("sni=${encodeQueryValue(it)}") }
                if (profile.wsPath != null || profile.wsHost != null) {
                    add("type=ws")
                    profile.wsPath?.let { add("path=${encodeQueryValue(it)}") }
                    profile.wsHost?.let { add("host=${encodeQueryValue(it)}") }
                }
                if (profile.mux == "smux_v1") {
                    add("mux=smux_v1")
                }
                if (profile.innerCipher == "aes-256-gcm" || profile.innerCipher == "chacha20-ietf-poly1305") {
                    add("encryption=${encodeQueryValue("ss;${profile.innerCipher};${profile.password}")}")
                }
            }.joinToString("&")
        val query = if (params.isEmpty()) "" else "?$params"
        return "trojan-go://${profile.password}@${profile.server}:${profile.serverPort}" +
            "$query#${encodeFragment(profile.displayName)}"
    }

    private fun encodeMieru(profile: ProxyProfile.Mieru): String {
        // Emit the invented Mieru URI form (round-trips through
        // ProxyUriCodec.parseMieru): mieru://<user>:<pass>@<host>:<port>
        //   ?protocol=tcp&mux=middle&mtu=1400#<name>
        // The username and password are percent-encoded; the URI query key is
        // `mux` while the profile field is `multiplexing`. All three query params
        // are emitted explicitly so the share link is self-describing.
        val params =
            buildList {
                add("protocol=${encodeQueryValue(profile.protocol)}")
                add("mux=${encodeQueryValue(profile.multiplexing)}")
                add("mtu=${profile.mtu}")
            }.joinToString("&")
        return "mieru://${encodeQueryValue(profile.username)}:${encodeQueryValue(profile.password)}" +
            "@${profile.server}:${profile.serverPort}?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeHysteriaV1(profile: ProxyProfile.HysteriaV1): String {
        // Emit the legacy Hysteria v1 URI form (round-trips through
        // ProxyUriCodec.parseHysteriaV1):
        //   hysteria://<host>:<port>?auth=...&protocol=udp&obfs=...
        //     &upmbps=10&downmbps=50&authtype=string&peer=<sni>&alpn=...#<name>
        // The auth payload and obfuscation are percent-encoded (base64 auth
        // payloads contain '+', '/', and '='). The auth-type encoding has no
        // canonical Hysteria v1 URI slot, so it is emitted as `authtype` to keep
        // the share link lossless. `peer` carries the SNI; `obfs`, `peer`, and
        // `alpn` are emitted only when set.
        val params =
            buildList {
                add("auth=${encodeQueryValue(profile.authPayload)}")
                add("authtype=${encodeQueryValue(profile.authType)}")
                add("protocol=${encodeQueryValue(profile.protocol)}")
                profile.obfuscation?.takeIf { it.isNotBlank() }?.let { add("obfs=${encodeQueryValue(it)}") }
                add("upmbps=${profile.upMbps}")
                add("downmbps=${profile.downMbps}")
                profile.sni?.takeIf { it.isNotBlank() }?.let { add("peer=${encodeQueryValue(it)}") }
                profile.alpn?.takeIf { it.isNotBlank() }?.let { add("alpn=${encodeQueryValue(it)}") }
            }.joinToString("&")
        return "hysteria://${profile.server}:${profile.serverPort}?$params#${encodeFragment(profile.displayName)}"
    }

    private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodeShadowsocks(profile: ProxyProfile.Shadowsocks): String {
        // SIP002: ss://base64url(method:password)@host:port#tag
        val userInfo = "${profile.method}:${profile.password}"
        val encoded =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(userInfo.toByteArray(Charsets.UTF_8))
        return "ss://$encoded@${profile.server}:${profile.serverPort}#${encodeFragment(profile.displayName)}"
    }

    private fun encodeFragment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
