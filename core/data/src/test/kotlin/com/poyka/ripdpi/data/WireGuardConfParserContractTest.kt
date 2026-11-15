package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.WireGuardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfParserContractTest {
    @Test
    fun `repeated wg quick lists append entries in source order`() {
        val conf =
            vanillaWireGuardConf
                .replace("Address =", "Address = fd00::2/128\nAddress =")
                .replace("DNS =", "DNS = 2001:db8::53\nDNS =")
                .replace("AllowedIPs =", "AllowedIPs = 2001:db8::/32\nAllowedIPs =")
        val parsed = WireGuardConfParser.parse(conf)
        assertEquals(listOf("fd00::2/128", "10.0.0.2/32"), parsed.interfaceSection.address)
        assertEquals(listOf("2001:db8::53", "1.1.1.1"), parsed.interfaceSection.dns)
        assertEquals(listOf("2001:db8::/32", "0.0.0.0/0"), parsed.peers.single().allowedIps)
    }

    @Test
    fun `malformed config errors omit input lines and values`() {
        listOf("fixture-text", "= fixture-text", "[fixture-text]", "MTU = fixture-text", "Jc = fixture-text").forEach {
            val failure =
                assertThrows(
                    IllegalArgumentException::class.java,
                ) { WireGuardConfParser.parse(wireGuardInterfaceConf(it)) }
            assertFalse(failure.message.orEmpty().contains("fixture-text"))
        }
    }

    @Test
    fun `ignored interface options keep canonical names and discard their values`() {
        val conf =
            wireGuardInterfaceConf(
                "table = off\nPreUp = fixture-host-option\nTABLE = auto\nListenPort = 51820",
            )
        val iface = WireGuardConfParser.parse(conf).interfaceSection
        assertEquals(listOf("Table", "PreUp", "ListenPort"), iface.ignoredOptions)
        assertEquals(51820, iface.listenPort)
        assertFalse(iface.toString().contains("fixture-host-option"))
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(wireGuardInterfaceConf("ListenPort = 65536"))
        }
    }

    @Test
    fun `a second interface cannot overwrite the first interface settings`() {
        val conf = vanillaWireGuardConf + "\n[ iNtErFaCe ] ; duplicate\nPrivateKey = different"
        assertThrows(IllegalArgumentException::class.java) { WireGuardConfParser.parse(conf) }
    }

    @Test
    fun `leading BOM and commented headers work for detection and parsing`() {
        val conf = "\uFEFF[ iNtErFaCe ] # comment\r\nPrivateKey = k\r\n[ Peer ]; comment\r\nPublicKey = p"
        assertTrue(WireGuardConfParser.looksLikeConf(conf))
        assertTrue(WireGuardConfParser.looksLikeConf(conf.substringBefore("[ Peer ]")))
        assertFalse(WireGuardConfParser.looksLikeConf("# [Interface]\n[Peer]\nPublicKey = p"))
        assertEquals(
            "p",
            WireGuardConfParser
                .parse(conf)
                .peers
                .single()
                .publicKey,
        )
    }

    @Test
    fun `explicit false flags and peer ranges identify AmneziaWG syntax`() {
        assertTrue(WireGuardConfParser.parse(wireGuardInterfaceConf("RandomTrailers = off")) is AmneziaWgConfig)
        assertTrue(WireGuardConfParser.parse(wireGuardInterfaceConf("DisableCookies = false")) is AmneziaWgConfig)
        listOf("0-0", "25-25", "25-35").forEach { interval ->
            val conf = wireGuardInterfaceConf("", "PersistentKeepalive = $interval")
            assertTrue(interval, WireGuardConfParser.parse(conf) is AmneziaWgConfig)
        }
        assertTrue(WireGuardConfParser.parse(wireGuardInterfaceConf("", "PersistentKeepalive = 25")) is WireGuardConfig)
    }

    @Test
    fun `final keepalive off clears both bounds`() {
        val conf = wireGuardInterfaceConf("", "PersistentKeepalive = 25-35\nPersistentKeepalive = oFf")
        val peer = WireGuardConfParser.parse(conf).peers.single()
        assertNull(peer.persistentKeepalive)
        assertNull(peer.persistentKeepaliveMax)
    }

    @Test
    fun `empty optional v3 values clear earlier settings without changing the dialect`() {
        val interfaceOptions =
            """
            RekeyTimeout = 5-7
            HeaderProtectionKey =
            ContentPaddingAddition =
            RekeyAfterTime =
            RekeyTimeout =
            RejectAfterTime =
            KeepaliveTimeout =
            MaxHandshakeAttempts =
            """.trimIndent()
        val conf = WireGuardConfParser.parse(wireGuardInterfaceConf(interfaceOptions)) as AmneziaWgConfig
        assertFalse(conf.awg.v3.isSet)
    }
}
