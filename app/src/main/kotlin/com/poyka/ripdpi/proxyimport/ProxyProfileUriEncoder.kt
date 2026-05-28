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
                userInfoUri("trojan-go", profile.password, profile.server, profile.serverPort, profile.displayName)
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
        // Produce: vless://<uuid>@<host>:<port>?security=reality&pbk=<key>&sid=<id>&sni=<sni>&flow=<flow>[&fp=<fp>]#<name>
        val params = buildList {
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

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
