package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ScannerUriDispatcher] and the shared [ImportPayloadClassifier]
 * behind it. Invalid content produces a redacted error (first 16 chars only) so the full
 * payload is never surfaced or logged.
 */
class ScannerUriDispatcherTest {
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

        assertEquals(ScannerScanResult.Invalid("http://malicious"), result)
    }

    @Test
    fun `short invalid payload is previewed in full and blank payload is invalid`() {
        assertEquals(ScannerScanResult.Invalid("nope"), ScannerUriDispatcher.dispatch("nope"))
        assertTrue(ScannerUriDispatcher.dispatch("   ") is ScannerScanResult.Invalid)
        assertTrue(ScannerUriDispatcher.dispatch("vless://") is ScannerScanResult.Invalid)
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
            "[Interface]\nPrivateKey = privkey==\n\n[Peer]\nPublicKey = peerpub==\nEndpoint = vpn.example.com:51820"

        assertEquals(ImportPayload.WireGuardConf(plain), recognized(plain))
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
    fun `payloads never print their secrets`() {
        assertTrue("privkey" !in ImportPayload.WireGuardConf(AwgConf).toString())
        val profile = recognized("trojan://hunter2-secret@example.com:443#QR")
        assertTrue("hunter2" !in profile.toString())
    }

    @Test
    fun `a share link inside a v2rayng install link imports as that profile`() {
        val link =
            "v2rayng://install-config?url=" + java.net.URLEncoder.encode("trojan://pw@example.com:443#T", "UTF-8")

        assertTrue(recognized(link) is ImportPayload.Profile)
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
