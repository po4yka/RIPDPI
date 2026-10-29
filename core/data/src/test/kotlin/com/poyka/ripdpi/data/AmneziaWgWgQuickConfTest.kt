package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.AmneziaWgUriCodec
import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.WireGuardConfig
import com.poyka.ripdpi.data.wireguard.toWgQuickConf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [toWgQuickConf] must write every field [WireGuardConfParser] reads, so the editor can import a share link. */
class AmneziaWgWgQuickConfTest {
    private val v3 =
        AmneziaWgV3Parameters(
            contentPaddingAddition = "10-100",
            rekeyAfterTime = "100-120",
            rekeyTimeout = "3-7",
            rejectAfterTime = "150-180",
            keepaliveTimeout = "5-15",
            maxHandshakeAttempts = "15-20",
            randomTrailers = true,
            disableCookies = true,
        )

    private val profile =
        AmneziaWgProfile(
            name = "Tokyo edge",
            host = "awg.example.com",
            port = 51820,
            privateKey = "cHJpdmF0ZS1rZXktZml4dHVyZS1ieXRlcy0wMDAwMDAwMA==",
            publicKey = "cHVibGljLWtleS1maXh0dXJlLWJ5dGVzLTAwMDAwMDAwMA==",
            presharedKey = "cHJlc2hhcmVkLWtleS1maXh0dXJlLWJ5dGVzLTAwMDAwMA==",
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            dns = listOf("1.1.1.1", "8.8.8.8"),
            mtu = 1280,
            address = listOf("10.8.1.2/32", "fd00::2/128"),
            persistentKeepalive = 25,
            persistentKeepaliveMax = 35,
            awg =
                AmneziaWgParameters(
                    jc = 5,
                    jmin = 10,
                    jmax = 50,
                    s1 = 12,
                    s2 = 13,
                    s3 = 0,
                    s4 = 0,
                    h1 = "100-199",
                    h2 = "200-299",
                    h3 = "300-300",
                    h4 = "400-499",
                    i1 = "<r 2><b 0x8580><rd 2><rc 2><t>",
                    i2 = "<b 0xf2>",
                    i3 = "<b 0xf3>",
                    i4 = "<b 0xf4>",
                    i5 = "<b 0xf5>",
                    v3 = v3,
                ),
        )

    private fun AmneziaWgConfig.toProfile(source: AmneziaWgProfile): AmneziaWgProfile {
        val peer = peers.single()
        return source.copy(
            privateKey = interfaceSection.privateKey,
            publicKey = peer.publicKey,
            presharedKey = peer.presharedKey,
            allowedIps = peer.allowedIps,
            dns = interfaceSection.dns,
            mtu = interfaceSection.mtu,
            address = interfaceSection.address,
            persistentKeepalive = peer.persistentKeepalive,
            persistentKeepaliveMax = peer.persistentKeepaliveMax,
            awg = awg,
        )
    }

    @Test
    fun `share link decodes, renders, and parses back to the same profile`() {
        val decoded = requireNotNull(AmneziaWgUriCodec.decode(AmneziaWgUriCodec.encode(profile)))
        assertEquals(profile, decoded)

        val parsed = WireGuardConfParser.parse(decoded.toWgQuickConf()) as AmneziaWgConfig

        assertEquals(profile, parsed.toProfile(profile))
        assertEquals("awg.example.com:51820", parsed.peers.single().endpoint)
    }

    @Test
    fun `header protection and non-zero S3-S4 render and parse without the activation floor`() {
        val key = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="
        val awg3 =
            profile.copy(
                host = "2001:db8::9",
                persistentKeepaliveMax = null,
                awg =
                    profile.awg.copy(
                        s3 = 12,
                        s4 = 12,
                        v3 = v3.copy(headerProtectionKey = AwgHeaderProtectionKey(key), disableCookies = false),
                    ),
            )
        val conf = awg3.toWgQuickConf()
        assertTrue(conf.contains("HeaderProtectionKey = $key\n"))
        assertTrue(conf.contains("PersistentKeepalive = 25\n"))
        assertTrue(conf.contains("Endpoint = [2001:db8::9]:51820\n"))

        val parsed = WireGuardConfParser.parse(conf) as AmneziaWgConfig
        assertEquals(awg3, parsed.toProfile(awg3))
    }

    @Test
    fun `vanilla profile renders a plain WireGuard conf`() {
        val vanilla = profile.copy(awg = AmneziaWgParameters(), presharedKey = null, persistentKeepalive = null)
        val parsed = WireGuardConfParser.parse(vanilla.toWgQuickConf())
        assertTrue(parsed is WireGuardConfig)
        assertEquals(vanilla.address, parsed.interfaceSection.address)
    }

    @Test
    fun `a value that would inject another conf line is refused`() {
        listOf("1.1.1.1\nPostUp = x", "1.1.1.1 # x", "1.1.1.1;x").forEach { dns ->
            assertThrows(dns, IllegalArgumentException::class.java) { profile.copy(dns = listOf(dns)).toWgQuickConf() }
        }
    }

    @Test
    fun `a share link rejects a zero-based keepalive range and preserves zero off`() {
        val uri = AmneziaWgUriCodec.encode(profile)
        assertTrue(uri.contains("persistent_keepalive=25-35"))
        val decoded = AmneziaWgUriCodec.decode(uri.replace("persistent_keepalive=25-35", "persistent_keepalive=0-35"))
        assertEquals(null, decoded)
        val off =
            requireNotNull(
                AmneziaWgUriCodec.decode(uri.replace("persistent_keepalive=25-35", "persistent_keepalive=0-0")),
            )
        assertEquals(0, off.persistentKeepalive)
        assertEquals(null, off.persistentKeepaliveMax)
    }
}
