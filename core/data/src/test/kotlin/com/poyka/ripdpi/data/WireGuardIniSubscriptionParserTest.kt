package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WireGuardIniSubscriptionParser]: the subscription-format
 * parser that takes a WireGuard `.conf` / INI payload and yields one profile
 * per `[Peer]`.
 *
 * Detection marker is `[Interface]` presence. The parser builds on the existing
 * [com.poyka.ripdpi.data.wireguard.WireGuardConfParser]; it does not reimplement
 * INI scanning.
 */
class WireGuardIniSubscriptionParserTest {
    private val groupId = "sub-wg-ini"

    private val singlePeerConf =
        """
        [Interface]
        PrivateKey = c2luZ2xlLXBlZXItcHJpdmF0ZS1rZXktZml4dHVyZQ==
        Address = 10.8.0.2/32
        DNS = 1.1.1.1
        MTU = 1420

        [Peer]
        PublicKey = c2luZ2xlLXBlZXItcHVibGljLWtleS1maXh0dXJlMDE=
        AllowedIPs = 0.0.0.0/0
        Endpoint = wg1.example.com:51820
        PersistentKeepalive = 25
        """.trimIndent()

    private val multiPeerConf =
        """
        [Interface]
        PrivateKey = bXVsdGktcGVlci1wcml2YXRlLWtleS1maXh0dXJl
        Address = 10.9.0.2/32, fd00::2/128
        DNS = 1.1.1.1, 8.8.8.8

        [Peer]
        PublicKey = bXVsdGktcGVlci1wdWJsaWMta2V5LWZpeHR1cmUwMQ==
        AllowedIPs = 0.0.0.0/0
        Endpoint = wg-a.example.com:51820

        [Peer]
        PublicKey = bXVsdGktcGVlci1wdWJsaWMta2V5LWZpeHR1cmUwMg==
        AllowedIPs = 10.0.0.0/8, 192.168.0.0/16
        Endpoint = wg-b.example.com:51821
        PresharedKey = bXVsdGktcGVlci1wcmVzaGFyZWQta2V5LWZpeA==
        """.trimIndent()

    private val warpStyleConf =
        """
        [Interface]
        PrivateKey = d2FycC1zdHlsZS1wcml2YXRlLWtleS1maXh0dXJl
        Address = 172.16.0.2/32, 2606:4700:110:fixture::2/128
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = d2FycC1zdHlsZS1wdWJsaWMta2V5LWZpeHR1cmUwMQ==
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = engage.cloudflareclient.com:2408
        """.trimIndent()

    @Test
    fun `detects INI payload via Interface header presence`() {
        assertTrue(WireGuardIniSubscriptionParser.looksLikeWireGuardIni(singlePeerConf))
        assertFalse(WireGuardIniSubscriptionParser.looksLikeWireGuardIni("vless://x@h:1#n"))
        assertFalse(WireGuardIniSubscriptionParser.looksLikeWireGuardIni("{\"outbounds\":[]}"))
    }

    @Test
    fun `single-peer conf yields exactly one profile sharing the interface key`() {
        val result = WireGuardIniSubscriptionParser.parse(singlePeerConf, groupId)

        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.profiles.size)
        val profile = result.profiles.single()
        assertEquals(groupId, profile.groupId)
        assertEquals("c2luZ2xlLXBlZXItcHJpdmF0ZS1rZXktZml4dHVyZQ==", profile.interfacePrivateKey)
        assertEquals("c2luZ2xlLXBlZXItcHVibGljLWtleS1maXh0dXJlMDE=", profile.peerPublicKey)
        assertEquals("wg1.example.com", profile.server)
        assertEquals(51820, profile.serverPort)
        assertEquals(listOf("1.1.1.1"), profile.dns)
        assertEquals(1420, profile.mtu)
        assertEquals(25, profile.persistentKeepalive)
    }

    @Test
    fun `multi-peer conf yields one profile per peer sharing interface keypair`() {
        val result = WireGuardIniSubscriptionParser.parse(multiPeerConf, groupId)

        assertTrue(result.warnings.isEmpty())
        assertEquals(2, result.profiles.size)
        // Both peers share the same interface private key material.
        assertEquals(
            setOf("bXVsdGktcGVlci1wcml2YXRlLWtleS1maXh0dXJl"),
            result.profiles.map { it.interfacePrivateKey }.toSet(),
        )
        // Distinct peer public keys / endpoints.
        assertEquals(
            listOf("wg-a.example.com:51820", "wg-b.example.com:51821"),
            result.profiles.map { "${it.server}:${it.serverPort}" },
        )
        // Display name distinguishes by peer endpoint.
        assertTrue(result.profiles[0].displayName.contains("wg-a.example.com"))
        assertTrue(result.profiles[1].displayName.contains("wg-b.example.com"))
        // The preshared key on peer B is preserved.
        assertEquals("bXVsdGktcGVlci1wcmVzaGFyZWQta2V5LWZpeA==", result.profiles[1].peerPresharedKey)
    }

    @Test
    fun `WARP-style conf parses with dual-stack address and AllowedIPs`() {
        val result = WireGuardIniSubscriptionParser.parse(warpStyleConf, groupId)

        assertEquals(1, result.profiles.size)
        val profile = result.profiles.single()
        assertEquals("engage.cloudflareclient.com", profile.server)
        assertEquals(2408, profile.serverPort)
        assertEquals(listOf("0.0.0.0/0", "::/0"), profile.allowedIps)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:fixture::2/128"), profile.interfaceAddress)
        assertEquals(1280, profile.mtu)
    }

    @Test
    fun `DNS field absent yields empty dns list, not a failure`() {
        val noDns =
            """
            [Interface]
            PrivateKey = bm8tZG5zLXByaXZhdGUta2V5LWZpeHR1cmUwMQ==
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = bm8tZG5zLXB1YmxpYy1rZXktZml4dHVyZTAxMDE=
            AllowedIPs = 0.0.0.0/0
            Endpoint = nodns.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(noDns, groupId)

        assertEquals(1, result.profiles.size)
        assertTrue(
            result.profiles
                .single()
                .dns
                .isEmpty(),
        )
    }

    @Test
    fun `IPv4-only AllowedIPs preserved as per-profile routing hint`() {
        val ipv4Only =
            """
            [Interface]
            PrivateKey = aXB2NC1vbmx5LXByaXZhdGUta2V5LWZpeHR1cmU=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = aXB2NC1vbmx5LXB1YmxpYy1rZXktZml4dHVyZTA=
            AllowedIPs = 10.0.0.0/8
            Endpoint = v4.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(ipv4Only, groupId)

        assertEquals(listOf("10.0.0.0/8"), result.profiles.single().allowedIps)
    }

    @Test
    fun `malformed INI surfaces a typed error rather than throwing`() {
        val malformed = "[Interface]\nthis line has no equals sign"

        val result = WireGuardIniSubscriptionParser.parse(malformed, groupId)

        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(
            result.warnings
                .single()
                .reason
                .isNotBlank(),
        )
    }

    @Test
    fun `payload without Interface header is rejected as not a WireGuard INI`() {
        val result = WireGuardIniSubscriptionParser.parse("not a wireguard config", groupId)

        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(
            result.warnings
                .single()
                .reason
                .contains("[Interface]"),
        )
    }

    @Test
    fun `a peer missing its public key degrades to skip-and-warn, not full rejection`() {
        val oneBadPeer =
            """
            [Interface]
            PrivateKey = c2tpcC1hbmQtd2Fybi1wcml2YXRlLWtleS1maXg=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = c2tpcC1hbmQtd2Fybi1nb29kLXB1YmxpYy1rZXk=
            AllowedIPs = 0.0.0.0/0
            Endpoint = good.example.com:51820

            [Peer]
            AllowedIPs = 0.0.0.0/0
            Endpoint = bad.example.com:51820
            """.trimIndent()

        val result = WireGuardIniSubscriptionParser.parse(oneBadPeer, groupId)

        // The good peer still produces a profile; the bad peer is a warning.
        assertEquals(1, result.profiles.size)
        assertEquals("good.example.com", result.profiles.single().server)
        assertEquals(1, result.warnings.size)
    }
}
