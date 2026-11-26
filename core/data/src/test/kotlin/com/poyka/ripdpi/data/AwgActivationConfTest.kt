package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.toWgQuickConf
import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.WireGuardConfig
import com.poyka.ripdpi.data.wireguard.WireGuardInterface
import com.poyka.ripdpi.data.wireguard.WireGuardPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgActivationConfTest {
    private val privateKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val peerKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
    private val pskKey = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ="
    private val hpKey = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="

    private val awg3 =
        AwgActivationRequest(
            profileId = "awg-1",
            privateKey = privateKey,
            peerPublicKey = peerKey,
            presharedKey = pskKey,
            endpointHost = "2001:db8::9",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.1.2/32",
            interfaceAddressV6 = "fd00::2/128",
            dnsServers = listOf("1.1.1.1", "2606:4700:4700::1111"),
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            mtu = 1300,
            persistentKeepalive = 25,
            persistentKeepaliveMax = 35,
            obfuscation =
                AwgActivationObfuscation(
                    jc = 5,
                    jmin = 10,
                    jmax = 50,
                    s1 = 12,
                    s2 = 13,
                    s3 = 14,
                    s4 = 15,
                    h1 = "100-199",
                    h2 = "200-299",
                    h3 = "300-300",
                    h4 = "400-499",
                    i1 = "<r 2><b 0x8580><rd 2><rc 2><t>",
                    i2 = "<b 0xf2>",
                    i3 = "<b 0xf3>",
                    i4 = "<b 0xf4>",
                    i5 = "<b 0xf5>",
                    v3 =
                        AmneziaWgV3Parameters(
                            headerProtectionKey = AwgHeaderProtectionKey(hpKey),
                            contentPaddingAddition = "10-100",
                            rekeyAfterTime = "100-120",
                            rekeyTimeout = "3-7",
                            rejectAfterTime = "150-180",
                            keepaliveTimeout = "5-15",
                            maxHandshakeAttempts = "15-20",
                            randomTrailers = true,
                            disableCookies = true,
                        ),
                ),
        )

    @Test
    fun `an AmneziaWG 3 server writes its interface, peer and every obfuscation field`() {
        val parsed = WireGuardConfParser.parse(awg3.toWgQuickConf()) as AmneziaWgConfig

        assertEquals(
            WireGuardInterface(
                privateKey = privateKey,
                address = listOf("10.8.1.2/32", "fd00::2/128"),
                dns = listOf("1.1.1.1", "2606:4700:4700::1111"),
                mtu = 1300,
            ),
            parsed.interfaceSection,
        )
        assertEquals(
            listOf(
                WireGuardPeer(
                    publicKey = peerKey,
                    presharedKey = pskKey,
                    allowedIps = listOf("0.0.0.0/0", "::/0"),
                    endpoint = "[2001:db8::9]:51820",
                    persistentKeepalive = 25,
                    persistentKeepaliveMax = 35,
                ),
            ),
            parsed.peers,
        )
        val o = awg3.obfuscation
        assertEquals(
            AmneziaWgParameters(
                jc = 5,
                jmin = 10,
                jmax = 50,
                s1 = 12,
                s2 = 13,
                s3 = 14,
                s4 = 15,
                h1 = "100-199",
                h2 = "200-299",
                h3 = "300-300",
                h4 = "400-499",
                i1 = o.i1,
                i2 = o.i2,
                i3 = o.i3,
                i4 = o.i4,
                i5 = o.i5,
                v3 = o.v3,
            ),
            parsed.awg,
        )
    }

    @Test
    fun `a server without obfuscation writes a plain WireGuard conf`() {
        val plain = awg3.copy(obfuscation = AwgActivationObfuscation(), presharedKey = "", persistentKeepaliveMax = 0)
        val conf = plain.toWgQuickConf()

        val parsed = WireGuardConfParser.parse(conf)
        assertTrue(parsed is WireGuardConfig)
        assertEquals(null, parsed.peers.single().presharedKey)
        assertEquals(25, parsed.peers.single().persistentKeepalive)
        assertFalse(conf.contains("Jc"))
    }

    @Test
    fun `a server with no private key, a WebSocket carrier or an invalid field is not written`() {
        listOf(
            awg3.copy(privateKey = ""),
            awg3.copy(carrier = AwgActivationRequest.CARRIER_WS, carrierWsUrl = "wss://example.com/wg"),
            awg3.copy(allowedIps = emptyList()),
        ).forEach { request ->
            assertThrows(IllegalArgumentException::class.java) { request.toWgQuickConf() }
        }
    }
}
