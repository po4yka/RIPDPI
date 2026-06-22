package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // pbk is required since 7d78c28; security=reality without pbk is rejected.
        val uri =
            "vless://00000000-0000-0000-0000-000000000000@edge.example.com:443" +
                "?type=tcp&security=reality&pbk=TESTPUBLICKEY0011223344556677#prod-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("00000000-0000-0000-0000-000000000000", profile.uuid)
        assertEquals("prod-node", profile.displayName)
        assertEquals("TESTPUBLICKEY0011223344556677", profile.realityPublicKey)
        // sni defaults to the host and flow to xtls-rprx-vision when omitted.
        assertEquals("edge.example.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
    }

    @Test
    fun `removed legacy schemes vmess and trojan-go are rejected`() {
        // VMess and Trojan-Go were removed; their schemes now hit the else -> null
        // arm and are rejected so callers skip the line.
        val vmessStandard =
            ProxyUriCodec.parse(
                "vmess://22222222-2222-4222-8222-222222222222@std.example.com:443?security=aes-128-gcm&type=tcp#Std",
            )
        val payload =
            """{"v":"2","ps":"vmess-node","add":"vmess.example.com","port":"8443",""" +
                """"id":"11111111-1111-1111-1111-111111111111","net":"ws"}"""
        val vmessBase64 =
            ProxyUriCodec.parse(
                "vmess://" +
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(payload.toByteArray()),
            )
        val trojanGo =
            ProxyUriCodec.parse(
                "trojan-go://s3cr3t@tg.example.com:443?sni=edge.example.com&type=ws#TG",
            )

        assertNull("vmess:// must be rejected", vmessStandard)
        assertNull("base64 vmess:// must be rejected", vmessBase64)
        assertNull("trojan-go:// must be rejected", trojanGo)
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
    fun `parses ssh password-auth uri into an ssh profile`() {
        val uri = "ssh://ssh-user:ssh-pass-fixture@vps.example.com:22?auth=password&fp=SHA256:abc&strict=1#Bastion"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Ssh)
        profile as ProxyProfile.Ssh
        assertEquals("vps.example.com", profile.server)
        assertEquals(22, profile.serverPort)
        assertEquals("ssh-user", profile.username)
        assertEquals("password", profile.authType)
        assertEquals("ssh-pass-fixture", profile.password)
        assertNull(profile.privateKey)
        assertEquals("SHA256:abc", profile.hostKeyFingerprint)
        assertTrue(profile.strictHostKey)
        assertEquals("Bastion", profile.displayName)
    }

    @Test
    fun `parses ssh private-key-auth uri with pem and passphrase in query`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nLINE1\nLINE2\n-----END OPENSSH PRIVATE KEY-----"
        val uri =
            "ssh://ssh-user@vps.example.com:2222?auth=private_key" +
                "&key=${java.net.URLEncoder.encode(pem, "UTF-8")}" +
                "&passphrase=${java.net.URLEncoder.encode("pp fixture", "UTF-8")}#Key"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Ssh)
        profile as ProxyProfile.Ssh
        assertEquals("ssh-user", profile.username)
        assertEquals("private_key", profile.authType)
        assertNull(profile.password)
        assertEquals(pem, profile.privateKey)
        assertEquals("pp fixture", profile.privateKeyPassphrase)
        assertFalse(profile.strictHostKey)
    }

    @Test
    fun `bare hysteria v1 scheme is rejected while hysteria2 and hy2 still parse`() {
        // Hysteria v1 (the bare `hysteria://` scheme) was removed and now hits the
        // else -> null arm; the active Hysteria2 (`hysteria2://` / `hy2://`) is kept.
        val hysteriaV1 =
            ProxyUriCodec.parse("hysteria://h1.example.com:2096?auth=hy1-auth-fixture&protocol=faketcp#HysteriaV1")
        val hysteria2 = ProxyUriCodec.parse("hysteria2://hy2-pass@hy2.example.com:8443?insecure=1#hy2")
        val hy2 = ProxyUriCodec.parse("hy2://hy2-pass@hy2alias.example.com:9000#hy2-alias")

        assertNull("bare hysteria:// (v1) must be rejected", hysteriaV1)
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
