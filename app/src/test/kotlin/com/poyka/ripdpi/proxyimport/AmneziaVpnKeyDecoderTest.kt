package com.poyka.ripdpi.proxyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaVpnKeyDecoderTest {
    private val conf = "[Interface]\nPrivateKey = k==\nDNS = \$PRIMARY_DNS, \$SECONDARY_DNS\n\n[Peer]\nPublicKey = p=="

    @Test
    fun `dns placeholders are filled from the key`() {
        val key = AmneziaVpnKeyFixtures.vpnKey(conf, dns1 = "1.1.1.1", dns2 = "9.9.9.9")

        assertEquals(
            "[Interface]\nPrivateKey = k==\nDNS = 1.1.1.1, 9.9.9.9\n\n[Peer]\nPublicKey = p==",
            AmneziaVpnKeyDecoder.confFromKey(key),
        )
    }

    @Test
    fun `an unset dns placeholder drops the dns line`() {
        assertEquals(
            "[Interface]\nPrivateKey = k==\n\n[Peer]\nPublicKey = p==",
            AmneziaVpnKeyDecoder.confFromKey(AmneziaVpnKeyFixtures.vpnKey(conf)),
        )
    }

    @Test
    fun `wireguard container is accepted too`() {
        val json = AmneziaVpnKeyFixtures.keyJson("[Interface]\nPrivateKey = k==", "amnezia-wireguard", "wireguard")
        val compressed = AmneziaVpnKeyFixtures.qCompress(json.toByteArray())

        assertEquals("[Interface]\nPrivateKey = k==", AmneziaVpnKeyDecoder.confFromCompressed(compressed))
    }

    @Test
    fun `other protocols and corrupt keys yield null`() {
        val openVpn = AmneziaVpnKeyFixtures.keyJson("client\ndev tun", "amnezia-openvpn", "openvpn")

        assertNull(AmneziaVpnKeyDecoder.confFromCompressed(AmneziaVpnKeyFixtures.qCompress(openVpn.toByteArray())))
        assertNull(AmneziaVpnKeyDecoder.confFromKey("vpn://not-base64-!!"))
        assertNull(AmneziaVpnKeyDecoder.confFromKey("vpn://" + AmneziaVpnKeyFixtures.base64Url(ByteArray(12))))
        assertNull(AmneziaVpnKeyDecoder.confFromKey("https://example.com"))
    }

    @Test
    fun `a declared size above the cap is rejected before inflating`() {
        val bomb = AmneziaVpnKeyFixtures.qCompress(ByteArray(300 * 1024))

        assertNull(AmneziaVpnKeyDecoder.confFromCompressed(bomb))
    }

    @Test
    fun `a qr series is assembled in any order and reports progress`() {
        val compressed =
            AmneziaVpnKeyFixtures.qCompress(
                AmneziaVpnKeyFixtures.keyJson(conf, dns1 = "1.1.1.1").toByteArray(),
            )
        val half = compressed.size / 2
        val first = AmneziaVpnKeyFixtures.qrChunk(2, 0, compressed.copyOfRange(0, half))
        val second = AmneziaVpnKeyFixtures.qrChunk(2, 1, compressed.copyOfRange(half, compressed.size))
        val assembler = AmneziaQrChunkAssembler()

        val partial = assembler.accept(second)
        val complete = assembler.accept(first)

        assertEquals(AmneziaQrChunkAssembler.Result.Partial(AmneziaQrChunkAssembler.Progress(1, 2)), partial)
        assertTrue((complete as AmneziaQrChunkAssembler.Result.Complete).compressed.contentEquals(compressed))
        assertNull(assembler.accept("vless://id@example.com:443"))
    }
}
