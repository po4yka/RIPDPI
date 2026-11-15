package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.uri.AmneziaWgUriCodec
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ScannerUriDispatcher] and the shared [ImportPayloadClassifier]
 * behind it. Invalid content produces a fixed redacted error so input text and credentials
 * are never surfaced or logged.
 */
class ScannerUriDispatcherTest {
    @Test
    fun `nested profile links reject malformed UTF-8 before decoding`() {
        val wrapper = "v2rayng://install-config?url=trojan%3A%2F%2Ffixture%FF%40example.com%3A443"

        assertTrue(ScannerUriDispatcher.dispatch(wrapper) is ScannerScanResult.Invalid)
    }

    @Test
    fun `vless qr payload resolves to a profile import request`() {
        val payload = recognized("vless://11111111-2222-3333-4444-555555555555@example.com:443#QR")

        assertTrue((payload as ImportPayload.Profile).request.profile is ProxyProfile.Vless)
    }

    @Test
    fun `every codec scheme is scannable, including anytls`() {
        val payload = recognized("anytls://secret@example.com:443?sni=example.com#QR")

        assertTrue(payload is ImportPayload.Profile)
    }

    @Test
    fun `non https link yields a redacted invalid result`() {
        val result =
            ScannerUriDispatcher.dispatch(
                "http://malicious.example.com/very/long/path/that/should/be/redacted",
            )

        assertEquals(ScannerScanResult.Invalid("<redacted>"), result)
    }

    @Test
    fun `short invalid payload and blank input use a fixed redacted marker`() {
        assertEquals(ScannerScanResult.Invalid("<redacted>"), ScannerUriDispatcher.dispatch("nope"))
        assertTrue(ScannerUriDispatcher.dispatch("   ") is ScannerScanResult.Invalid)
        assertTrue(ScannerUriDispatcher.dispatch("vless://") is ScannerScanResult.Invalid)
    }

    @Test
    fun `invalid credential links and arbitrary text never expose their prefixes`() {
        val secret =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(32) { it.toByte() })
        listOf(
            "amneziawg://$secret@vpn.example.com:51820?public_key=peer-key",
            "AMNEZIAWG://$secret@vpn.example.com:51820?public_key=peer-key&wire_profile=unknown",
            "$secret is not a supported import",
        ).forEach { input ->
            val result = ScannerUriDispatcher.dispatch(input) as ScannerScanResult.Invalid

            assertEquals("<redacted>", result.redactedPreview)
            assertTrue(secret.take(4) !in result.toString())
        }
    }

    @Test
    fun `https subscription qr payload resolves to a long-lived subscription`() {
        val payload = recognized("https://sub.example.com/s/token123") as ImportPayload.Subscription

        assertEquals(PendingSubscriptionImportRequest("https://sub.example.com/s/token123", "", false), payload.request)
    }

    @Test
    fun `https bootstrap link keeps the bootstrap kind`() {
        val payload = recognized("https://sub.example.com/bootstrap/one-time") as ImportPayload.Subscription

        assertTrue(payload.request.bootstrap)
    }

    @Test
    fun `official sing-box link takes its name from the fragment`() {
        val payload =
            recognized("sing-box://import-remote-profile?url=https%3A%2F%2Fsub.example.com%2Fs#My%20Fleet")
                as ImportPayload.Subscription

        assertEquals(PendingSubscriptionImportRequest("https://sub.example.com/s", "My Fleet", false), payload.request)
    }

    @Test
    fun `ripdpi subscription deep link resolves to a subscription request`() {
        val payload = recognized("ripdpi://import?sub=https%3A%2F%2Fsub.example.com%2Fs") as ImportPayload.Subscription

        assertEquals("https://sub.example.com/s", payload.request.url)
    }

    @Test
    fun `amneziawg conf qr payload resolves to a conf import`() {
        assertEquals(ImportPayload.WireGuardConf(AwgConf), recognized(AwgConf))
    }

    @Test
    fun `plain wireguard conf is scanned too, the editor validates it`() {
        val plain =
            "[ Interface ] # tunnel\nPrivateKey = privkey==\n\n[ pEeR ] ; peer\n" +
                "PublicKey = peerpub==\nEndpoint = vpn.example.com:51820"

        assertEquals(ImportPayload.WireGuardConf(plain), recognized(plain))
        assertTrue(ScannerUriDispatcher.dispatch("[Interface]\n# [Peer]") is ScannerScanResult.Invalid)
    }

    @Test
    fun `conf classification uses the parser section grammar for raw and wrapped text`() {
        val conf =
            AwgConf
                .replace("[Interface]", "[ interface ] # interface")
                .replace("[Peer]", "[ Peer ]; peer")
        val withBom = "\uFEFF$conf"
        val payload = ImportPayload.WireGuardConf(conf)

        assertEquals(payload, recognized(withBom))
        assertEquals(ClipboardImportResult.Importable(payload), ClipboardImportClassifier.classify(withBom))
        val wrapped = AmneziaVpnKeyFixtures.vpnKey(conf)
        assertEquals(payload, recognized(wrapped))
    }

    @Test
    fun `conf routing requires both uncommented section headers`() {
        val partial = "[Interface] # interface\nPrivateKey = fixture-key"

        assertNull(ImportPayloadClassifier.classify(partial))
        assertNull(ImportPayloadClassifier.classify("# $partial\n[Peer]"))
    }

    @Test
    fun `amneziavpn vpn key resolves to the embedded conf`() {
        val key = AmneziaVpnKeyFixtures.vpnKey(conf = "[Interface]\nPrivateKey = k==\n\n[Peer]\nPublicKey = p==")

        assertEquals(
            ImportPayload.WireGuardConf("[Interface]\nPrivateKey = k==\n\n[Peer]\nPublicKey = p=="),
            recognized(key),
        )
    }

    @Test
    fun `amneziawg link opens the editor with an equivalent conf`() {
        val link =
            AmneziaWgUriCodec.encode(
                AmneziaWgProfile(
                    name = "Home",
                    host = "vpn.example.com",
                    port = 51820,
                    privateKey = "cHJpdmtleQ==",
                    publicKey = "cGVlcnB1Yg==",
                    address = listOf("10.8.0.2/32"),
                ),
            )

        val conf = (recognized(link) as ImportPayload.WireGuardConf).conf

        assertTrue(conf.contains("Endpoint = vpn.example.com:51820"))
        assertTrue(conf.contains("PrivateKey = cHJpdmtleQ=="))
    }

    @Test
    fun `amneziawg values that cannot be written to conf are rejected without throwing`() {
        val profile =
            AmneziaWgProfile(
                name = "Home",
                host = "vpn.example.com",
                port = 51820,
                privateKey = "private-key",
                publicKey = "peer-key",
                address = listOf("10.8.0.2/32"),
            )

        listOf('\n', '\r', '#', ';').forEach { forbidden ->
            val value = "unsafe${forbidden}value"
            listOf(
                profile.copy(privateKey = value),
                profile.copy(publicKey = value),
                profile.copy(presharedKey = value),
                profile.copy(address = listOf(value)),
                profile.copy(allowedIps = listOf(value)),
                profile.copy(dns = listOf(value)),
            ).forEach { invalid ->
                val uri = AmneziaWgUriCodec.encode(invalid)
                assertNull(ImportPayloadClassifier.classify(uri))
                assertTrue(ScannerUriDispatcher.dispatch(uri) is ScannerScanResult.Invalid)
                assertEquals(
                    ClipboardImportResult.UnknownContent("amneziawg"),
                    ClipboardImportClassifier.classify(uri),
                )
            }
        }
    }

    @Test
    fun `payloads never print their secrets`() {
        assertTrue("privkey" !in ImportPayload.WireGuardConf(AwgConf).toString())
        val profile = recognized("trojan://hunter2-secret@example.com:443#QR")
        assertTrue("hunter2" !in profile.toString())
    }

    @Test
    fun `a share link inside a v2rayng install link imports as that profile`() {
        val target = "trojan://p%2Bw%25@example.com:8443?sni=edge.example#T%252B"
        val link = "v2rayng://install-config?url=" + java.net.URLEncoder.encode(target, "UTF-8")

        val profile = (recognized(link) as ImportPayload.Profile).request.profile as ProxyProfile.Trojan

        assertEquals("example.com", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("p+w%", profile.password)
        assertEquals("edge.example", profile.serverName)
        assertEquals("T%2B", profile.displayName)
        assertNull(SubscriptionLinkClassifier.classify(link))
    }

    @Test
    fun `nested share links keep the first query value and accept wrapper case`() {
        val target = java.net.URLEncoder.encode("trojan://pw@example.com:443#T", "UTF-8")
        val link = "V2RAYNG://INSTALL-CONFIG?url=$target&url=invalid"
        val profile = (recognized(link) as ImportPayload.Profile).request.profile as ProxyProfile.Trojan

        assertEquals("pw", profile.password)
        assertEquals("T", profile.displayName)
        assertTrue(
            ScannerUriDispatcher.dispatch("v2rayng://install-config?url=&url=$target") is ScannerScanResult.Invalid,
        )
    }

    @Test
    fun `nested share links reject malformed wrappers and unsupported targets`() {
        val target = java.net.URLEncoder.encode("trojan://pw@example.com:443#T", "UTF-8")
        val invalid =
            listOf(
                "other://install-config?url=$target",
                "v2rayng://unknown?url=$target",
                "v2rayng://user@install-config?url=$target",
                "v2rayng://install-config:1234?url=$target",
                "v2rayng://install-config?url=trojan%3A%2F%2F",
                "v2rayng://install-config?url=file%3A%2F%2F%2Ftmp%2Fprivate",
                "v2rayng://install-config?url=http%3A%2F%2Fplain.example",
                "v2rayng://install-config?url=%ZZ",
                "v2rayng://install-config?url=" + java.net.URLEncoder.encode(target, "UTF-8"),
            )
        invalid.forEach { assertTrue(ScannerUriDispatcher.dispatch(it) is ScannerScanResult.Invalid) }
    }

    @Test
    fun `HTTPS install targets remain subscriptions`() {
        val link = "v2rayng://install-config?url=https%3A%2F%2Fsub.example%2Fbootstrap%2Ftoken&name=Fleet"
        val payload = recognized(link) as ImportPayload.Subscription

        assertEquals(
            PendingSubscriptionImportRequest("https://sub.example/bootstrap/token", "Fleet", true),
            payload.request,
        )
    }

    private fun recognized(text: String): ImportPayload =
        (ScannerUriDispatcher.dispatch(text) as ScannerScanResult.Recognized).payload

    private companion object {
        val AwgConf =
            """
            [Interface]
            PrivateKey = privkey==
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 50
            S2 = 100
            H1 = 1000000001
            H2 = 1000000002
            H3 = 1000000003
            H4 = 1000000004

            [Peer]
            PublicKey = peerpub==
            Endpoint = vpn.example.com:51820
            """.trimIndent()
    }
}
