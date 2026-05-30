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
    fun `parses vmess base64 json uri into a vmess profile`() {
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

        assertTrue(profile is ProxyProfile.Vmess)
        profile as ProxyProfile.Vmess
        assertEquals("vmess.example.com", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("11111111-1111-1111-1111-111111111111", profile.uuid)
        assertEquals("vmess-node", profile.displayName)
        assertEquals("ws", profile.transport)
    }

    @Test
    fun `parses standard vmess uri into a vmess profile`() {
        val uri =
            "vmess://22222222-2222-4222-8222-222222222222@std.example.com:443?security=aes-128-gcm&type=tcp#Std"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Vmess)
        profile as ProxyProfile.Vmess
        assertEquals("std.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("22222222-2222-4222-8222-222222222222", profile.uuid)
        assertEquals("tcp", profile.transport)
    }

    @Test
    fun `parses standard trojan-go uri into a trojan-go profile`() {
        val uri =
            "trojan-go://s3cr3t@tg.example.com:443?sni=edge.example.com&type=ws&path=/tg&host=cdn.example.com" +
                "&mux=smux_v1&encryption=ss;aes-256-gcm;s3cr3t#TG"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.TrojanGo)
        profile as ProxyProfile.TrojanGo
        assertEquals("tg.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("s3cr3t", profile.password)
        assertEquals("edge.example.com", profile.sni)
        assertEquals("/tg", profile.wsPath)
        assertEquals("cdn.example.com", profile.wsHost)
        assertEquals("smux_v1", profile.mux)
        assertEquals("aes-256-gcm", profile.innerCipher)
    }

    @Test
    fun `parses standard mieru uri into a mieru profile`() {
        val uri =
            "mieru://mieru-user:mieru-pass-fixture@m.example.com:2096?protocol=udp&mux=high&mtu=1380#Mieru"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Mieru)
        profile as ProxyProfile.Mieru
        assertEquals("m.example.com", profile.server)
        assertEquals(2096, profile.serverPort)
        assertEquals("mieru-user", profile.username)
        assertEquals("mieru-pass-fixture", profile.password)
        assertEquals("udp", profile.protocol)
        assertEquals("high", profile.multiplexing)
        assertEquals(1380, profile.mtu)
    }

    @Test
    fun `parses bare hysteria uri into a hysteria v1 profile`() {
        // The bare `hysteria://` scheme is the legacy Hysteria v1 form; `auth` and
        // `obfs` are percent-encoded in the query. `authtype` is honoured when
        // present; `peer` carries the SNI.
        val uri =
            "hysteria://h1.example.com:2096?auth=hy1-auth-fixture&authtype=base64&protocol=faketcp" +
                "&obfs=hy1-obfs-fixture&upmbps=20&downmbps=100&peer=sni.example.com&alpn=h3#HysteriaV1"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue("expected HysteriaV1, got ${profile?.javaClass?.simpleName}", profile is ProxyProfile.HysteriaV1)
        profile as ProxyProfile.HysteriaV1
        assertEquals("h1.example.com", profile.server)
        assertEquals(2096, profile.serverPort)
        assertEquals("base64", profile.authType)
        assertEquals("hy1-auth-fixture", profile.authPayload)
        assertEquals("hy1-obfs-fixture", profile.obfuscation)
        assertEquals("faketcp", profile.protocol)
        assertEquals(20, profile.upMbps)
        assertEquals(100, profile.downMbps)
        assertEquals("sni.example.com", profile.sni)
        assertEquals("h3", profile.alpn)
    }

    @Test
    fun `bare hysteria scheme defaults auth type to string when authtype is absent`() {
        val uri = "hysteria://h1.example.com:2096?auth=hy1-auth-fixture#HysteriaV1"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.HysteriaV1)
        profile as ProxyProfile.HysteriaV1
        assertEquals("string", profile.authType)
        assertEquals("udp", profile.protocol)
        assertEquals(10, profile.upMbps)
        assertEquals(50, profile.downMbps)
        assertNull(profile.obfuscation)
        assertNull(profile.sni)
        assertNull(profile.alpn)
    }

    @Test
    fun `hysteria2 and hy2 schemes still parse to Hysteria2 not HysteriaV1`() {
        val hysteria2 = ProxyUriCodec.parse("hysteria2://hy2-pass@hy2.example.com:8443?insecure=1#hy2")
        val hy2 = ProxyUriCodec.parse("hy2://hy2-pass@hy2alias.example.com:9000#hy2-alias")

        assertTrue("hysteria2:// must route to Hysteria2", hysteria2 is ProxyProfile.Hysteria2)
        assertTrue("hy2:// must route to Hysteria2", hy2 is ProxyProfile.Hysteria2)
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
