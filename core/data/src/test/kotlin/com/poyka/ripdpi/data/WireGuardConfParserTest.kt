package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.WireGuardConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val vanillaConf =
        """
        [Interface]
        PrivateKey = aGVsbG8td29ybGQtcHJpdmF0ZS1rZXktZml4dHVyZQ==
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        MTU = 1420

        [Peer]
        PublicKey = aGVsbG8td29ybGQtcHVibGljLWtleS1maXh0dXJlMDE=
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820
        PersistentKeepalive = 25
        """.trimIndent()

    @Test
    fun `vanilla conf with no AWG keys parses as WireGuardConfig`() {
        val parsed = WireGuardConfParser.parse(vanillaConf)

        assertTrue(parsed is WireGuardConfig)
        parsed as WireGuardConfig
        assertEquals("aGVsbG8td29ybGQtcHJpdmF0ZS1rZXktZml4dHVyZQ==", parsed.interfaceSection.privateKey)
        assertEquals(listOf("10.0.0.2/32"), parsed.interfaceSection.address)
        assertEquals(listOf("1.1.1.1"), parsed.interfaceSection.dns)
        assertEquals(1420, parsed.interfaceSection.mtu)
        assertEquals(1, parsed.peers.size)
        assertEquals("aGVsbG8td29ybGQtcHVibGljLWtleS1maXh0dXJlMDE=", parsed.peers[0].publicKey)
        assertEquals(listOf("0.0.0.0/0"), parsed.peers[0].allowedIps)
        assertEquals("vpn.example.com:51820", parsed.peers[0].endpoint)
        assertEquals(25, parsed.peers[0].persistentKeepalive)
    }

    @Test
    fun `conf with multiple peers parses every peer`() {
        val multiPeer =
            """
            [Interface]
            PrivateKey = cHJpdmF0ZS1rZXktbXVsdGktcGVlci1maXh0dXJlMQ==
            Address = 10.0.0.3/32

            [Peer]
            PublicKey = cGVlci1vbmUtcHVibGljLWtleS1maXh0dXJlLTAwMDE=
            AllowedIPs = 10.0.0.0/24
            Endpoint = a.example.com:51820

            [Peer]
            PublicKey = cGVlci10d28tcHVibGljLWtleS1maXh0dXJlLTAwMDI=
            AllowedIPs = 192.168.0.0/24
            Endpoint = b.example.com:51820
            """.trimIndent()

        val parsed = WireGuardConfParser.parse(multiPeer)

        assertTrue(parsed is WireGuardConfig)
        parsed as WireGuardConfig
        assertEquals(2, parsed.peers.size)
        assertEquals("a.example.com:51820", parsed.peers[0].endpoint)
        assertEquals("b.example.com:51820", parsed.peers[1].endpoint)
        assertEquals(listOf("192.168.0.0/24"), parsed.peers[1].allowedIps)
    }

    @Test
    fun `conf with all AWG keys parses as AmneziaWgConfig`() {
        val awgConf =
            """
            [Interface]
            PrivateKey = YXdnLXByaXZhdGUta2V5LWFsbC1maWVsZHMtZml4dHVyZQ==
            Address = 10.8.0.2/32
            DNS = 8.8.8.8
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 30
            S2 = 50
            S3 = 0
            S4 = 0
            H1 = 1234567
            H2 = 2345678
            H3 = 3456789
            H4 = 4567890
            I1 = deadbeef
            I2 = cafebabe
            I3 = 0badf00d
            I4 = feedface
            I5 = 8badf00d

            [Peer]
            PublicKey = YXdnLXBlZXItcHVibGljLWtleS1maXh0dXJlLTAwMDE=
            AllowedIPs = 0.0.0.0/0
            Endpoint = awg.example.com:51820
            """.trimIndent()

        val parsed = WireGuardConfParser.parse(awgConf)

        assertTrue(parsed is AmneziaWgConfig)
        parsed as AmneziaWgConfig
        assertEquals(4, parsed.awg.jc)
        assertEquals(40, parsed.awg.jmin)
        assertEquals(70, parsed.awg.jmax)
        assertEquals(30, parsed.awg.s1)
        assertEquals(50, parsed.awg.s2)
        assertEquals(0, parsed.awg.s3)
        assertEquals(0, parsed.awg.s4)
        assertEquals(1234567L, parsed.awg.h1)
        assertEquals(2345678L, parsed.awg.h2)
        assertEquals(3456789L, parsed.awg.h3)
        assertEquals(4567890L, parsed.awg.h4)
        assertEquals("deadbeef", parsed.awg.i1)
        assertEquals("cafebabe", parsed.awg.i2)
        assertEquals("0badf00d", parsed.awg.i3)
        assertEquals("feedface", parsed.awg.i4)
        assertEquals("8badf00d", parsed.awg.i5)
        // The vanilla WireGuard field set is still parsed.
        assertEquals(listOf("10.8.0.2/32"), parsed.interfaceSection.address)
        assertEquals(1280, parsed.interfaceSection.mtu)
        assertEquals(1, parsed.peers.size)
    }

    @Test
    fun `conf rejects non-zero S3 or S4 for Android arm64 safety`() {
        fun conf(
            s3: Int,
            s4: Int,
        ) = """
            [Interface]
            PrivateKey = YXdnLXByaXZhdGUta2V5LWFybTY0LWZpeHR1cmU=
            S3 = $s3
            S4 = $s4

            [Peer]
            PublicKey = YXdnLXBlZXItcHVibGljLWtleS1hcm02NC1maXh0dXJl
            Endpoint = awg.example.com:51820
            """.trimIndent()

        val s3Error = assertThrows(IllegalArgumentException::class.java) { WireGuardConfParser.parse(conf(1, 0)) }
        val s4Error = assertThrows(IllegalArgumentException::class.java) { WireGuardConfParser.parse(conf(0, 1)) }

        assertTrue(s3Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(s4Error.message.orEmpty().contains("amneziawg-go#110"))
    }

    @Test
    fun `conf with only Jc still parses as AmneziaWgConfig`() {
        val partialAwg =
            """
            [Interface]
            PrivateKey = YXdnLXByaXZhdGUta2V5LW9ubHktamMtZml4dHVyZTAx
            Address = 10.9.0.2/32
            Jc = 3

            [Peer]
            PublicKey = YXdnLXBlZXItcHVibGljLWtleS1qYy1vbmx5LWZpeHR1cmU=
            AllowedIPs = 0.0.0.0/0
            Endpoint = jc.example.com:51820
            """.trimIndent()

        val parsed = WireGuardConfParser.parse(partialAwg)

        assertTrue(parsed is AmneziaWgConfig)
        parsed as AmneziaWgConfig
        assertEquals(3, parsed.awg.jc)
        assertNull(parsed.awg.jmin)
        assertNull(parsed.awg.s1)
        assertNull(parsed.awg.h1)
        assertNull(parsed.awg.i1)
    }

    @Test
    fun `unknown keys are ignored, not a hard error`() {
        val withUnknownKey =
            """
            [Interface]
            PrivateKey = dW5rbm93bi1rZXktY29uZi1wcml2YXRlLWZpeHR1cmU=
            Address = 10.0.0.2/32
            SomeFutureKey = whatever-value

            [Peer]
            PublicKey = dW5rbm93bi1rZXktcGVlci1wdWJsaWMtZml4dHVyZTA=
            AllowedIPs = 0.0.0.0/0
            Endpoint = unknown.example.com:51820
            AnotherUnknownPeerKey = ignored
            """.trimIndent()

        val parsed = WireGuardConfParser.parse(withUnknownKey)

        assertTrue(parsed is WireGuardConfig)
        parsed as WireGuardConfig
        assertEquals("10.0.0.2/32", parsed.interfaceSection.address.single())
        assertEquals(1, parsed.peers.size)
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val withComments =
            """
            # this is a comment
            [Interface]
            PrivateKey = Y29tbWVudC1jb25mLXByaXZhdGUta2V5LWZpeHR1cmU=

            # another comment
            Address = 10.0.0.2/32

            [Peer]
            # peer comment
            PublicKey = Y29tbWVudC1jb25mLXBlZXItcHVibGljLWtleS1maXg=
            AllowedIPs = 0.0.0.0/0
            """.trimIndent()

        val parsed = WireGuardConfParser.parse(withComments)

        assertTrue(parsed is WireGuardConfig)
        parsed as WireGuardConfig
        assertEquals("Y29tbWVudC1jb25mLXByaXZhdGUta2V5LWZpeHR1cmU=", parsed.interfaceSection.privateKey)
        assertEquals(1, parsed.peers.size)
    }

    @Test
    fun `key=value pair without surrounding whitespace parses`() {
        val tight =
            """
            [Interface]
            PrivateKey=dGlnaHQtY29uZi1wcml2YXRlLWtleS1maXh0dXJlMDE=
            Address=10.0.0.2/32
            Jc=2

            [Peer]
            PublicKey=dGlnaHQtY29uZi1wZWVyLXB1YmxpYy1rZXktZml4dHVy
            AllowedIPs=0.0.0.0/0
            """.trimIndent()

        val parsed = WireGuardConfParser.parse(tight)

        assertTrue(parsed is AmneziaWgConfig)
        parsed as AmneziaWgConfig
        assertEquals(2, parsed.awg.jc)
        assertEquals("dGlnaHQtY29uZi1wcml2YXRlLWtleS1maXh0dXJlMDE=", parsed.interfaceSection.privateKey)
    }

    @Test
    fun `malformed line without equals sign throws`() {
        val malformed =
            """
            [Interface]
            PrivateKey = bWFsZm9ybWVkLWNvbmYtcHJpdmF0ZS1rZXktZml4dHVy
            this-line-has-no-equals-sign
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(malformed)
        }
    }

    @Test
    fun `key=value before any section header throws`() {
        val noSection =
            """
            PrivateKey = bm8tc2VjdGlvbi1jb25mLXByaXZhdGUta2V5LWZpeA==
            Address = 10.0.0.2/32
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(noSection)
        }
    }

    @Test
    fun `non-numeric AWG field throws`() {
        val badJc =
            """
            [Interface]
            PrivateKey = YmFkLWpjLWNvbmYtcHJpdmF0ZS1rZXktZml4dHVyZTA=
            Address = 10.0.0.2/32
            Jc = not-a-number
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(badJc)
        }
    }

    @Test
    fun `negative AWG field throws`() {
        val negativeJmin =
            """
            [Interface]
            PrivateKey = bmVnLWptaW4tY29uZi1wcml2YXRlLWtleS1maXh0dXI=
            Address = 10.0.0.2/32
            Jmin = -5
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(negativeJmin)
        }
    }

    @Test
    fun `H field exceeding 4-byte unsigned range throws`() {
        val oversizeH1 =
            """
            [Interface]
            PrivateKey = b3ZlcnNpemUtaDEtY29uZi1wcml2YXRlLWtleS1maXg=
            Address = 10.0.0.2/32
            H1 = 4294967296
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(oversizeH1)
        }
    }

    @Test
    fun `non-hex I field throws`() {
        val badI1 =
            """
            [Interface]
            PrivateKey = YmFkLWkxLWNvbmYtcHJpdmF0ZS1rZXktZml4dHVyZTA=
            Address = 10.0.0.2/32
            I1 = nothex!!
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse(badI1)
        }
    }

    @Test
    fun `empty conf throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parse("   \n  \n")
        }
    }

    @Test
    fun `WireGuardConfig serializes round-trip`() {
        val parsed = WireGuardConfParser.parse(vanillaConf) as WireGuardConfig

        val encoded = json.encodeToString(WireGuardConfig.serializer(), parsed)
        val decoded = json.decodeFromString(WireGuardConfig.serializer(), encoded)

        assertEquals(parsed, decoded)
    }

    @Test
    fun `AmneziaWgConfig serializes round-trip`() {
        val awgConf =
            """
            [Interface]
            PrivateKey = c2VyaWFsaXplLWF3Zy1jb25mLXByaXZhdGUta2V5LWZpeA==
            Address = 10.8.0.2/32
            Jc = 4
            S1 = 30
            H1 = 1234567
            I1 = deadbeef

            [Peer]
            PublicKey = c2VyaWFsaXplLWF3Zy1jb25mLXBlZXItcHVibGljLWtleQ==
            AllowedIPs = 0.0.0.0/0
            Endpoint = awg.example.com:51820
            """.trimIndent()
        val parsed = WireGuardConfParser.parse(awgConf) as AmneziaWgConfig

        val encoded = json.encodeToString(AmneziaWgConfig.serializer(), parsed)
        val decoded = json.decodeFromString(AmneziaWgConfig.serializer(), encoded)

        assertEquals(parsed, decoded)
    }
}
