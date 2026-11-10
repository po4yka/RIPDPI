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
        // sni defaults to the host and flow to empty when omitted.
        assertEquals("edge.example.com", profile.serverName)
        assertEquals("", profile.flow)
        assertFalse(NativeVlessCapabilities.supportsUdp("reality", "reality_tcp", profile.flow))
    }

    @Test
    fun `rejects vless reality uri with unsupported wire transport instead of coercing to tcp`() {
        // grpc/ws/h2/httpupgrade REALITY transports are not implemented by RIPDPI's
        // VLESS+REALITY client; the codec must reject them rather than silently
        // activating a plain-TCP REALITY profile that would speak the wrong wire
        // transport to the server.
        val grpc =
            ProxyUriCodec.parse(
                "vless://00000000-0000-0000-0000-000000000000@edge.example.com:443" +
                    "?type=grpc&security=reality#grpc-node",
            )
        val ws =
            ProxyUriCodec.parse(
                "vless://00000000-0000-0000-0000-000000000000@edge.example.com:443" +
                    "?type=ws&security=reality#ws-node",
            )
        val h2 =
            ProxyUriCodec.parse(
                "vless://00000000-0000-0000-0000-000000000000@edge.example.com:443" +
                    "?type=h2&security=reality#h2-node",
            )
        val httpupgrade =
            ProxyUriCodec.parse(
                "vless://00000000-0000-0000-0000-000000000000@edge.example.com:443" +
                    "?type=httpupgrade&security=reality#httpupgrade-node",
            )

        assertNull("type=grpc must be rejected", grpc)
        assertNull("type=ws must be rejected", ws)
        assertNull("type=h2 must be rejected", h2)
        assertNull("type=httpupgrade must be rejected", httpupgrade)
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
            "mieru://mieru-user:mieru-pass-fixture@m.example.com:2096?protocol=tcp&mux=high&mtu=1380#Mieru"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.Mieru)
        profile as ProxyProfile.Mieru
        assertEquals("m.example.com", profile.server)
        assertEquals(2096, profile.serverPort)
        assertEquals("mieru-user", profile.username)
        assertEquals("mieru-pass-fixture", profile.password)
        assertEquals("tcp", profile.protocol)
        assertEquals("high", profile.multiplexing)
        assertEquals(1380, profile.mtu)
    }

    @Test
    fun `parses upstream simple mierus link using its first TCP binding`() {
        // Example link and equivalent JSON from upstream docs/client-install.md (v3.36.0).
        val uri =
            "mierus://baozi:manlianpenfen@1.2.3.4?handshake-mode=HANDSHAKE_NO_WAIT&mtu=1400" +
                "&multiplexing=MULTIPLEXING_HIGH&port=6666&port=9998-9999&port=6489&port=4896" +
                "&profile=default&protocol=TCP&protocol=TCP&protocol=UDP&protocol=UDP" +
                "&traffic-pattern=CCoQARoECAEQCiIYCAMQASoIMDAwMTAyMDMqCDA0MDUwNjA3"

        val profile = ProxyUriCodec.parse(uri) as ProxyProfile.Mieru

        assertEquals("1.2.3.4", profile.server)
        assertEquals(6666, profile.serverPort)
        assertEquals("baozi", profile.username)
        assertEquals("manlianpenfen", profile.password)
        assertEquals("tcp", profile.protocol)
        assertEquals("high", profile.multiplexing)
        assertEquals(1400, profile.mtu)
    }

    @Test
    fun `mierus link takes the first port of a TCP range and rejects UDP-only or invalid links`() {
        val base = "mierus://u:p@h.example?profile=default"
        val range = ProxyUriCodec.parse("$base&port=7000-7010&protocol=TCP&port=80&protocol=UDP") as ProxyProfile.Mieru
        assertEquals(7000, range.serverPort)
        assertEquals("middle", range.multiplexing)
        assertEquals(1400, range.mtu)

        assertNull("UDP-only", ProxyUriCodec.parse("$base&port=6489&protocol=UDP"))
        assertNull("unpaired port", ProxyUriCodec.parse("$base&port=1&protocol=TCP&port=2"))
        assertNull("bad protocol", ProxyUriCodec.parse("$base&port=1&protocol=QUIC"))
        assertNull("bad port", ProxyUriCodec.parse("$base&port=70000&protocol=TCP"))
        assertNull("reversed range", ProxyUriCodec.parse("$base&port=9-8&protocol=TCP"))
        assertNull("bad mtu", ProxyUriCodec.parse("$base&port=1&protocol=TCP&mtu=100"))
        assertNull("bad multiplexing", ProxyUriCodec.parse("$base&port=1&protocol=TCP&multiplexing=HIGH"))
        assertNull("no password", ProxyUriCodec.parse("mierus://u@h.example?port=1&protocol=TCP"))
    }

    @Test
    fun `parses upstream standard mieru link`() {
        // Example link from upstream docs/client-install.md (v3.36.0): active profile "default",
        // user baozi, domain localhost, TCP 6666 first, mtu 1400, MULTIPLEXING_HIGH.
        val uri =
            "mieru://CpsBCgdkZWZhdWx0ElgKBWJhb3ppEg1tYW5saWFucGVuZmVuGkA0MGFiYWM0MGY1OWRhNTVkYWQ2YTk5ODMxYTUx" +
                "MTY1MjJmYmM4MGUzODViYjFhYjE0ZGM1MmRiMzY4ZjczOGE0Gi8SCWxvY2FsaG9zdBoFCIo0EAIaDRACGgk5OTk5LTk5OTka" +
                "BQjZMhABGgUIoCYQASD4CioCCAQSB2RlZmF1bHQYnUYguAgwBTgA"

        val profile = ProxyUriCodec.parse(uri) as ProxyProfile.Mieru

        assertEquals("localhost", profile.server)
        assertEquals(6666, profile.serverPort)
        assertEquals("baozi", profile.username)
        assertEquals("manlianpenfen", profile.password)
        assertEquals("tcp", profile.protocol)
        assertEquals("high", profile.multiplexing)
        assertEquals(1400, profile.mtu)
        assertNull("truncated protobuf", ProxyUriCodec.parse(uri.dropLast(40)))
    }

    @Test
    fun `mieru export is a mierus link that imports back unchanged`() {
        val original =
            ProxyProfile.Mieru(
                id = "id",
                displayName = "Home",
                groupId = "",
                server = "2001:db8::1",
                serverPort = 2096,
                username = "us er",
                password = "p@ss:word-fixture",
                multiplexing = "low",
                mtu = 1380,
            )

        val uri = ProxyUriCodec.encode(original)
        val parsed = ProxyUriCodec.parse(uri) as ProxyProfile.Mieru

        assertTrue(uri, uri.startsWith("mierus://"))
        assertTrue(uri, uri.contains("multiplexing=MULTIPLEXING_LOW"))
        assertEquals(original.copy(id = parsed.id), parsed)
    }

    @Test
    fun `mieru import rejects a user name over 64 UTF-8 bytes`() {
        val limit = "a".repeat(64)
        val tooLong = "a".repeat(65)
        val atLimit = ProxyUriCodec.parse("mierus://$limit:p@h.example?port=1&protocol=TCP") as ProxyProfile.Mieru
        assertEquals(limit, atLimit.username)
        assertNull(ProxyUriCodec.parse("mierus://$tooLong:p@h.example?port=1&protocol=TCP"))
        assertNull(ProxyUriCodec.parse("mieru://$tooLong:p@h.example:443"))
        // 33 two-byte characters are 66 bytes.
        assertNull(ProxyUriCodec.parse("mierus://${"\u00e9".repeat(33)}:p@h.example?port=1&protocol=TCP"))
    }

    @Test
    fun `mieru links accept lowercase multiplexing tokens and reject legacy UDP`() {
        val low = ProxyUriCodec.parse("mierus://u:p@h.example?port=1&protocol=tcp&multiplexing=multiplexing_low")
        assertEquals("low", (low as ProxyProfile.Mieru).multiplexing)
        assertNull(ProxyUriCodec.parse("mieru://u:p@h.example:443?protocol=udp"))
    }

    @Test
    fun `standard mieru link uses the profile name and the first server with a TCP binding`() {
        fun varint(value: Int): ByteArray = byteArrayOf(value.toByte())

        fun len(
            field: Int,
            payload: ByteArray,
        ): ByteArray = byteArrayOf(((field shl 3) or 2).toByte()) + varint(payload.size) + payload

        fun text(
            field: Int,
            value: String,
        ): ByteArray = len(field, value.toByteArray())

        fun binding(
            port: Int,
            protocol: Int,
        ): ByteArray = len(3, byteArrayOf(0x08, port.toByte(), 0x10, protocol.toByte()))

        val udpOnly = len(3, text(2, "udp.example") + binding(53, 1))
        val tcp = len(3, text(1, "9.9.9.9") + binding(80, 2))
        val user = len(2, text(1, "u") + text(2, "p"))
        val profile = len(1, text(1, "Work") + user + udpOnly + tcp)
        val config = profile + text(2, "Work")
        val uri =
            "mieru://" +
                java.util.Base64
                    .getEncoder()
                    .encodeToString(config)

        val parsed = ProxyUriCodec.parse(uri) as ProxyProfile.Mieru

        assertEquals("Work", parsed.displayName)
        assertEquals("9.9.9.9", parsed.server)
        assertEquals(80, parsed.serverPort)
    }

    @Test
    fun `legacy mieru link rejects invalid options instead of coercing them`() {
        val base = "mieru://u:p@h.example:443"
        assertNull(ProxyUriCodec.parse("$base?mtu=99"))
        assertNull(ProxyUriCodec.parse("$base?mux=extreme"))
        assertNull(ProxyUriCodec.parse("$base?protocol=quic"))
        assertEquals(443, (ProxyUriCodec.parse(base) as ProxyProfile.Mieru).serverPort)
    }

    @Test
    fun `parses naive https link with empty password headers and name`() {
        val uri =
            "naive+https://alice:@n.example.com:8443/?padding=true" +
                "&extra-headers=X-Token%3A%20s3cret%0D%0AX-Other%3A%20v#My%20Naive"

        val profile = ProxyUriCodec.parse(uri) as ProxyProfile.Naive

        assertEquals("n.example.com", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("alice", profile.username)
        assertEquals("", profile.password)
        assertEquals("X-Token: s3cret\r\nX-Other: v", profile.extraHeaders)
        assertEquals("My Naive", profile.displayName)
        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile)) as ProxyProfile.Naive
        assertEquals(profile.copy(id = ""), reparsed.copy(id = ""))
    }

    @Test
    fun `naive link defaults the port and rejects bad input`() {
        assertEquals(443, (ProxyUriCodec.parse("naive+https://u:p@h.example") as ProxyProfile.Naive).serverPort)
        assertNull("no userinfo", ProxyUriCodec.parse("naive+https://h.example:443"))
        assertNull("reserved header", ProxyUriCodec.parse("naive+https://u:p@h.example?extra-headers=Host%3A%20x"))
        assertNull("malformed header", ProxyUriCodec.parse("naive+https://u:p@h.example?extra-headers=nocolon"))
    }

    @Test
    fun `naive quic and concurrency links round trip and a pinned quic link is rejected`() {
        val uri = "naive+quic://u:p@h.example:8443/?insecure-concurrency=3#n"
        val profile = ProxyUriCodec.parse(uri) as ProxyProfile.Naive

        assertEquals(true to 3, profile.quic to profile.concurrency)
        assertEquals("naive+quic://u:p@h.example:8443/?insecure-concurrency=3#n", ProxyUriCodec.encode(profile))
        assertEquals(1, (ProxyUriCodec.parse("naive+https://u:p@h.example") as ProxyProfile.Naive).concurrency)
        val pinnedQuic = "naive+quic://u:p@h.example?pinSHA256=" + "a".repeat(64)
        assertNull("pin needs the Rust helper", ProxyUriCodec.parse(pinnedQuic))
        val pinnedConcurrency = "naive+https://u:p@h.example?insecure-concurrency=2&pinSHA256=" + "a".repeat(64)
        assertNull("pin needs the Rust helper", ProxyUriCodec.parse(pinnedConcurrency))
        for (bad in listOf("0", "5", "x")) {
            assertNull(bad, ProxyUriCodec.parse("naive+https://u:p@h.example?insecure-concurrency=$bad"))
        }
    }

    @Test
    fun `naive link keeps a literal plus and rejects a malformed escape`() {
        val uri = "naive+https://u+x:p%2Bq+r@h.example?extra-headers=X-A%3A%20a+b#n+m"
        val profile = ProxyUriCodec.parse(uri) as ProxyProfile.Naive

        assertEquals("u+x", profile.username)
        assertEquals("p+q+r", profile.password)
        assertEquals("X-A: a+b", profile.extraHeaders)
        assertEquals("n+m", profile.displayName)
        assertEquals("p+q+r", (ProxyUriCodec.parse(ProxyUriCodec.encode(profile)) as ProxyProfile.Naive).password)
        assertNull(ProxyUriCodec.parse("naive+https://u:p%zz@h.example"))
        // The shared pre-pass turns a stray % in query and fragment into a literal; only userinfo can be malformed.
        assertNull(ProxyUriCodec.parse("naive+https://u%zz:p@h.example"))
    }

    @Test
    fun `naive link rejects ports outside the valid range`() {
        assertNull(ProxyUriCodec.parse("naive+https://u:p@h.example:65536"))
        assertNull(ProxyUriCodec.parse("naive+https://u:p@h.example:0"))
        assertEquals(65535, (ProxyUriCodec.parse("naive+https://u:p@h.example:65535") as ProxyProfile.Naive).serverPort)
    }

    @Test
    fun `naive link carries the server pin and rejects a malformed one`() {
        val pin = "ab".repeat(32)
        val profile =
            ProxyUriCodec.parse("naive+https://u:p@h.example?pinSHA256=${pin.uppercase()}") as ProxyProfile.Naive

        assertEquals(pin, profile.serverPinSha256)
        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile)) as ProxyProfile.Naive
        assertEquals(pin, reparsed.serverPinSha256)
        assertTrue(!ProxyUriCodec.encode(profile.copy(serverPinSha256 = "")).contains("pinSHA256"))
        assertNull(ProxyUriCodec.parse("naive+https://u:p@h.example?pinSHA256=abcd"))
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
