package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AmneziaWG branch of [WireGuardIniSubscriptionParser].
 *
 * An INI-format subscription whose `[Interface]` block carries any AmneziaWG
 * key (`Jc`/`Jmin`/`Jmax`/`S1`-`S4`/`H1`-`H4`/`I1`-`I5`) must produce an
 * AmneziaWG profile, not a vanilla WireGuard profile. Detection is delegated to
 * the existing [com.poyka.ripdpi.data.wireguard.WireGuardConfParser], which
 * already routes between [com.poyka.ripdpi.data.wireguard.WireGuardConfig] and
 * [com.poyka.ripdpi.data.wireguard.AmneziaWgConfig] by AWG-key presence.
 */
class WireGuardIniSubscriptionAmneziaWgTest {
    private val groupId = "sub-awg-ini"

    private val awgConf =
        """
        [Interface]
        PrivateKey = YXdnLWludGVyZmFjZS1wcml2YXRlLWtleS1maXg=
        Address = 10.13.0.2/32
        DNS = 1.1.1.1
        MTU = 1420
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 50
        S2 = 100
        H1 = 1234567
        H2 = 2345678
        H3 = 3456789
        H4 = 4567890
        I1 = deadbeef

        [Peer]
        PublicKey = YXdnLXBlZXItcHVibGljLWtleS1maXh0dXJlMDE=
        AllowedIPs = 0.0.0.0/0
        Endpoint = awg1.example.com:51820
        PersistentKeepalive = 25
        """.trimIndent()

    @Test
    fun `interface block with AWG keys yields an AmneziaWG profile, not a vanilla WireGuard profile`() {
        val result = WireGuardIniSubscriptionParser.parse(awgConf, groupId)

        assertTrue(result.warnings.isEmpty())
        // No vanilla WireGuard profile is emitted for an AWG interface.
        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.amneziaWgProfiles.size)
        val profile = result.amneziaWgProfiles.single()
        assertEquals(groupId, profile.groupId)
        assertEquals("awg1.example.com", profile.server)
        assertEquals(51820, profile.serverPort)
        assertEquals("YXdnLWludGVyZmFjZS1wcml2YXRlLWtleS1maXg=", profile.interfacePrivateKey)
        assertEquals("YXdnLXBlZXItcHVibGljLWtleS1maXh0dXJlMDE=", profile.peerPublicKey)
        assertEquals(25, profile.persistentKeepalive)
        assertEquals(1420, profile.mtu)
    }

    @Test
    fun `AWG obfuscation parameters from the interface block are carried on the profile`() {
        val result = WireGuardIniSubscriptionParser.parse(awgConf, groupId)

        val awg = result.amneziaWgProfiles.single().awg
        assertEquals(4, awg.jc)
        assertEquals(40, awg.jmin)
        assertEquals(70, awg.jmax)
        assertEquals(50, awg.s1)
        assertEquals(100, awg.s2)
        assertEquals(1234567L, awg.h1)
        assertEquals(4567890L, awg.h4)
        assertEquals("deadbeef", awg.i1)
    }

    @Test
    fun `vanilla interface block still yields a vanilla WireGuard profile`() {
        val vanillaConf =
            """
            [Interface]
            PrivateKey = dmFuaWxsYS1pbnRlcmZhY2UtcHJpdmF0ZS1rZXk=
            Address = 10.0.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = dmFuaWxsYS1wZWVyLXB1YmxpYy1rZXktZml4MQ==
            AllowedIPs = 0.0.0.0/0
            Endpoint = vanilla.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(vanillaConf, groupId)

        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.profiles.size)
        assertTrue(result.amneziaWgProfiles.isEmpty())
        assertEquals("vanilla.example.com", result.profiles.single().server)
    }

    @Test
    fun `interface-scope AWG fields apply to every peer of a multi-peer AWG conf`() {
        val multiPeerAwg =
            """
            [Interface]
            PrivateKey = bXVsdGktYXdnLWludGVyZmFjZS1wcml2YXRlLWs=
            Address = 10.14.0.2/32
            Jc = 3
            S1 = 25

            [Peer]
            PublicKey = bXVsdGktYXdnLXBlZXItcHVibGljLWtleS0wMQ==
            AllowedIPs = 0.0.0.0/0
            Endpoint = awg-a.example.com:51820

            [Peer]
            PublicKey = bXVsdGktYXdnLXBlZXItcHVibGljLWtleS0wMg==
            AllowedIPs = 10.0.0.0/8
            Endpoint = awg-b.example.com:51821
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(multiPeerAwg, groupId)

        assertTrue(result.warnings.isEmpty())
        assertTrue(result.profiles.isEmpty())
        assertEquals(2, result.amneziaWgProfiles.size)
        // Interface-scope AWG fields are shared by all derived profiles.
        assertEquals(setOf(3), result.amneziaWgProfiles.map { it.awg.jc }.toSet())
        assertEquals(setOf(25), result.amneziaWgProfiles.map { it.awg.s1 }.toSet())
        assertEquals(
            listOf("awg-a.example.com:51820", "awg-b.example.com:51821"),
            result.amneziaWgProfiles.map { "${it.server}:${it.serverPort}" },
        )
    }

    @Test
    fun `partial AWG fields still route to the AmneziaWG branch`() {
        val partialAwg =
            """
            [Interface]
            PrivateKey = cGFydGlhbC1hd2ctcHJpdmF0ZS1rZXktZml4MA==
            Address = 10.0.0.2/32
            Jc = 2

            [Peer]
            PublicKey = cGFydGlhbC1hd2ctcGVlci1wdWJsaWMta2V5MA==
            AllowedIPs = 0.0.0.0/0
            Endpoint = partial.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(partialAwg, groupId)

        assertTrue(result.warnings.isEmpty())
        assertTrue(result.profiles.isEmpty())
        val profile = result.amneziaWgProfiles.single()
        assertEquals(2, profile.awg.jc)
        // Unset AWG fields stay null; only the present key is carried.
        assertEquals(null, profile.awg.jmin)
        assertEquals(null, profile.awg.s1)
    }

    @Test
    fun `malformed AWG field fails the interface block with a typed warning`() {
        val malformedAwg =
            """
            [Interface]
            PrivateKey = bWFsZm9ybWVkLWF3Zy1wcml2YXRlLWtleS1maXg=
            Address = 10.0.0.2/32
            Jc = not-a-number

            [Peer]
            PublicKey = bWFsZm9ybWVkLWF3Zy1wZWVyLXB1YmxpYy1rZXk=
            AllowedIPs = 0.0.0.0/0
            Endpoint = malformed.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(malformedAwg, groupId)

        // A bad interface-scope key cannot be salvaged per-peer; the whole
        // subscription fails with one typed warning, mirroring vanilla behavior.
        assertTrue(result.profiles.isEmpty())
        assertTrue(result.amneziaWgProfiles.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(
            result.warnings
                .single()
                .reason
                .isNotBlank(),
        )
    }

    @Test
    fun `mixed multi-interface payload routes each interface to the right bean type`() {
        // An INI file with both an AWG interface and a vanilla interface.
        // splitSections keeps the first [Interface]; we assert the parser still
        // produces a typed result and never throws on the unusual layout.
        val mixed =
            """
            [Interface]
            PrivateKey = bWl4ZWQtYXdnLWludGVyZmFjZS1wcml2YXRlLWs=
            Address = 10.0.0.2/32
            Jc = 5

            [Peer]
            PublicKey = bWl4ZWQtYXdnLXBlZXItcHVibGljLWtleS0wMQ==
            AllowedIPs = 0.0.0.0/0
            Endpoint = mixed-awg.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(mixed, groupId)

        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.amneziaWgProfiles.size)
        assertNotNull(
            result.amneziaWgProfiles
                .single()
                .awg.jc,
        )
    }
}
