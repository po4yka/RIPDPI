package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProxyUriCodec], the per-scheme proxy URI codec. Covers each
 * supported scheme plus malformed/unknown-scheme handling.
 */
class ProxyUriCodecTest {
    @Test
    fun `parses vless reality uri into a vless-reality profile`() {
        // security=reality (with no pbk) routes a vless URI to the REALITY variant.
        val uri =
            "vless://00000000-0000-0000-0000-000000000000@edge.example.com:443" +
                "?type=tcp&security=reality#prod-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("00000000-0000-0000-0000-000000000000", profile.uuid)
        assertEquals("prod-node", profile.displayName)
        // sni defaults to the host and flow to xtls-rprx-vision when omitted.
        assertEquals("edge.example.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
    }

    @Test
    fun `parses vmess base64 json uri into a vless profile`() {
        // vmess://<base64 of {v,ps,add,port,id,...}>
        val payload =
            """{"v":"2","ps":"vmess-node","add":"vmess.example.com","port":"8443",""" +
                """"id":"11111111-1111-1111-1111-111111111111","net":"ws"}"""
        val encoded =
            java.util.Base64
                .getEncoder()
                .encodeToString(payload.toByteArray())
        val uri = "vmess://$encoded"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Vless)
        profile as ProxyProfile.Vless
        assertEquals("vmess.example.com", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("11111111-1111-1111-1111-111111111111", profile.uuid)
        assertEquals("vmess-node", profile.displayName)
    }

    @Test
    fun `parses ss sip002 uri into a shadowsocks profile`() {
        // ss://base64(method:password)@host:port#tag
        val userInfo =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("aes-256-gcm:ss-secret".toByteArray())
        val uri = "ss://$userInfo@ss.example.com:8388#ss-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Shadowsocks)
        profile as ProxyProfile.Shadowsocks
        assertEquals("ss.example.com", profile.server)
        assertEquals(8388, profile.serverPort)
        assertEquals("aes-256-gcm", profile.method)
        assertEquals("ss-secret", profile.password)
        assertEquals("ss-node", profile.displayName)
    }

    @Test
    fun `parses ss uri with plain userinfo into a shadowsocks profile`() {
        val uri = "ss://aes-128-gcm:plain-secret@ss2.example.com:9999#plain-ss"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Shadowsocks)
        profile as ProxyProfile.Shadowsocks
        assertEquals("ss2.example.com", profile.server)
        assertEquals(9999, profile.serverPort)
        assertEquals("aes-128-gcm", profile.method)
        assertEquals("plain-secret", profile.password)
    }

    @Test
    fun `rejects ss uri with unsupported stream cipher method`() {
        val userInfo =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("aes-256-cfb:legacy-secret".toByteArray())
        val uri = "ss://$userInfo@legacy.example.com:8388#legacy-ss"

        assertNull(ProxyUriCodec.parse(uri))
    }

    @Test
    fun `parses trojan uri into a trojan profile`() {
        val uri = "trojan://trojan-pass@trojan.example.com:443?sni=trojan.example.com#tj-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Trojan)
        profile as ProxyProfile.Trojan
        assertEquals("trojan.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("trojan-pass", profile.password)
        assertEquals("tj-node", profile.displayName)
    }

    @Test
    fun `parses hysteria2 uri into a hysteria2 profile`() {
        val uri = "hysteria2://hy2-pass@hy2.example.com:8443?insecure=1#hy2-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Hysteria2)
        profile as ProxyProfile.Hysteria2
        assertEquals("hy2.example.com", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("hy2-pass", profile.password)
        assertEquals("hy2-node", profile.displayName)
    }

    @Test
    fun `parses hy2 short scheme alias into a hysteria2 profile`() {
        val uri = "hy2://hy2-pass@hy2alias.example.com:9000#hy2-alias"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Hysteria2)
        profile as ProxyProfile.Hysteria2
        assertEquals("hy2alias.example.com", profile.server)
        assertEquals(9000, profile.serverPort)
        assertEquals("hy2-pass", profile.password)
    }

    @Test
    fun `parses tuic uri into a raw config profile`() {
        val uri =
            "tuic://22222222-2222-2222-2222-222222222222:tuic-pass" +
                "@tuic.example.com:443?congestion_control=bbr#tuic-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.RawConfig)
        profile as ProxyProfile.RawConfig
        assertEquals("tuic-node", profile.displayName)
        assertTrue(profile.config.contains("tuic.example.com"))
    }

    @Test
    fun `parses anytls uri into an anytls profile`() {
        val uri = "anytls://anytls-pass@anytls.example.com:443?sni=front.example#anytls-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.AnyTls)
        profile as ProxyProfile.AnyTls
        assertEquals("anytls.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("front.example", profile.serverName)
        assertEquals("anytls-pass", profile.password)
        assertEquals("anytls-node", profile.displayName)
    }

    @Test
    fun `unknown scheme returns null`() {
        assertNull(ProxyUriCodec.parse("wireguard://something@host:51820"))
        assertNull(ProxyUriCodec.parse("not-a-uri-at-all"))
        assertNull(ProxyUriCodec.parse(""))
    }

    @Test
    fun `malformed uri of known scheme returns null`() {
        assertNull(ProxyUriCodec.parse("vless://"))
        assertNull(ProxyUriCodec.parse("trojan://@:"))
        assertNull(ProxyUriCodec.parse("ss://%%%not-base64%%%"))
        assertNull(ProxyUriCodec.parse("vless://uuid@host:not-a-port"))
    }

    @Test
    fun `display name falls back to host when fragment absent`() {
        val uri = "trojan://pass@fallback.example.com:443"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Trojan)
        assertEquals("fallback.example.com", (profile as ProxyProfile.Trojan).displayName)
    }
}
