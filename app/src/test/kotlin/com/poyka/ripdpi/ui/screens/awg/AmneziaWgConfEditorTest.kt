package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.uri.AmneziaWgUriCodec
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import com.poyka.ripdpi.proxyimport.ImportPayload
import com.poyka.ripdpi.proxyimport.ImportPayloadClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Tests for WireGuard and AmneziaWG configuration import into the editor. */
class AmneziaWgConfEditorTest {
    @Test
    fun `importing absent or off keepalive replaces an earlier invalid or positive value`() {
        for (previous in listOf("0-1", "25-35")) {
            for (line in listOf("", "PersistentKeepalive = off")) {
                val conf =
                    "[Interface]\nPrivateKey = $AwgPrivateTestKey\nAddress = 10.8.0.2/32\n" +
                        "[Peer]\nPublicKey = $AwgPeerTestKey\nEndpoint = vpn.example.com:51820\n" +
                        "AllowedIPs = 0.0.0.0/0\n$line"
                val edited = activatableAwgEditor().updateField(AwgEditorField.PERSISTENT_KEEPALIVE, previous)
                val imported = (edited.importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor
                assertEquals("", imported.rawText(AwgEditorField.PERSISTENT_KEEPALIVE))
                assertTrue(imported.isActivatable())
                val request = imported.toActivationRequest("profile")
                assertEquals(0 to 0, request.persistentKeepalive to request.persistentKeepaliveMax)
            }
        }
    }

    @Test
    fun `conf import replaces old transport settings and preserves route policy`() {
        val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val peerKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val previous =
            activatableAwgEditor()
                .updateField(AwgEditorField.DNS, "192.0.2.53")
                .updateField(AwgEditorField.ALLOWED_IPS, "198.51.100.0/24")
                .updateField(AwgEditorField.MTU, "1280")
                .updateField(AwgEditorField.PERSISTENT_KEEPALIVE, "25")
                .selectCarrier("ws")
                .updateField(AwgEditorField.CARRIER_WS_URL, "wss://old.example/carrier")
        val cases =
            listOf(
                Triple("10.23.0.2/32", "AllowedIPs = 0.0.0.0/0", listOf("0.0.0.0/0")),
                Triple("10.23.0.2/32", "", emptyList()),
                Triple("10.23.0.2/32", "AllowedIPs =", emptyList()),
                Triple("fd23::2/128, 10.23.0.2/32", "", emptyList()),
            )
        for ((address, policy, expectedRoutes) in cases) {
            val conf =
                "[Interface]\nPrivateKey = $privateKey\nAddress = $address\nDNS =\n" +
                    "[Peer]\nPublicKey = $peerKey\nEndpoint = vpn.example.com:51820\n$policy"
            val imported = (previous.importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor
            val request = imported.toActivationRequest("replacement")
            val fresh =
                (
                    AmneziaWgEditorState.initial().importConf(
                        conf,
                        AwgTestCatalog,
                    ) as AwgConfImport.Imported
                ).editor

            assertEquals("10.23.0.2/32", request.interfaceAddressV4)
            assertEquals(emptyList<String>(), request.dnsServers)
            assertEquals(expectedRoutes, request.allowedIps)
            assertEquals(fresh.toActivationRequest("fresh").allowedIps, request.allowedIps)
            assertEquals(com.poyka.ripdpi.data.awg.AwgActivationRequest.DEFAULT_MTU, request.mtu)
            assertEquals(0, request.persistentKeepalive)
            assertEquals(AwgActivationRequest.CARRIER_UDP, request.carrier)
            assertEquals("", request.carrierWsUrl)
            assertEquals("", imported.rawText(AwgEditorField.CARRIER_WS_URL))
            if (expectedRoutes.isEmpty()) {
                assertTrue(imported.hasFieldError(AwgEditorField.ALLOWED_IPS))
                assertFalse(imported.isActivatable())
                assertThrows(IllegalArgumentException::class.java) { request.requireRuntimeReady() }

                val edited = imported.updateField(AwgEditorField.ALLOWED_IPS, "203.0.113.0/24")
                assertTrue(edited.isActivatable())
                assertEquals(listOf("203.0.113.0/24"), edited.toActivationRequest("edited").allowedIps)
            } else {
                request.requireRuntimeReady()
            }
        }
    }

    @Test
    fun `manual import clears an absent address instead of reusing the old identity address`() {
        val conf = "[Interface]\nPrivateKey = k==\n[Peer]\nPublicKey = p==\nEndpoint = vpn.example.com:51820"
        val imported = (activatableAwgEditor().importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor

        assertEquals("", imported.rawText(AwgEditorField.ADDRESS))
        assertFalse(imported.isActivatable())
        assertThrows(IllegalArgumentException::class.java) { imported.toActivationRequest("replacement") }
    }

    @Test
    fun `importConf fills every field from a parsed awg config`() {
        val conf =
            """
            [Interface]
            PrivateKey = privkey==
            Address = 10.0.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 50
            S2 = 100
            H1 = 1000000001
            H2 = 1000000002
            H3 = 1000000003
            H4 = 1000000004
            I1 = <b 0xab12>

            [Peer]
            PublicKey = peerpub==
            Endpoint = vpn.example.com:51820
            """.trimIndent()

        val state = (AmneziaWgEditorState.initial().importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor

        assertEquals("privkey==", state.form.interfacePrivateKey)
        assertEquals("peerpub==", state.form.peerPublicKey)
        assertEquals("vpn.example.com", state.form.server)
        assertEquals(51_820, state.form.serverPort)
        assertEquals(4, state.form.jc)
        assertEquals("<b 0xab12>", state.rawText(AwgEditorField.I1))
        // The conf's obfuscation params byte-match rtk_south, so the cohort is detected.
        assertEquals("rtk_south", state.form.cohortId)
        assertTrue(state.obfuscationLocked)
    }

    @Test
    fun `importConf seeds Address, DNS, AllowedIPs, MTU and keepalive (P1-12)`() {
        val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val conf =
            """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.0.0.2/32, fd00::2/128
            DNS = 1.1.1.1, 8.8.8.8
            MTU = 1280
            Jc = 7

            [Peer]
            PublicKey = $peerPublicKey
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
            """.trimIndent()

        val state = (AmneziaWgEditorState.initial().importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor

        // Previously these were silently discarded; a blank Address also blocked
        // activation. They must now be seeded into the editor raw text.
        assertEquals("10.0.0.2/32, fd00::2/128", state.rawText(AwgEditorField.ADDRESS))
        assertEquals("1.1.1.1, 8.8.8.8", state.rawText(AwgEditorField.DNS))
        assertEquals("1280", state.rawText(AwgEditorField.MTU))
        assertEquals("0.0.0.0/0, ::/0", state.rawText(AwgEditorField.ALLOWED_IPS))
        assertEquals("25", state.rawText(AwgEditorField.PERSISTENT_KEEPALIVE))

        val request = state.toActivationRequest(profileId = "awg-dual-stack")

        assertEquals("10.0.0.2/32", request.interfaceAddressV4)
        assertEquals("fd00::2/128", request.interfaceAddressV6)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), request.dnsServers)
        assertEquals(listOf("0.0.0.0/0", "::/0"), request.allowedIps)
        request.requireRuntimeReady()
    }

    @Test
    fun `an IPv6 share link reaches activation with its bare endpoint and port`() {
        val profile =
            AmneziaWgProfile(
                name = "IPv6 peer",
                host = "2001:db8::9",
                port = 51820,
                privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
                address = listOf("10.8.0.2/32"),
                allowedIps = listOf("0.0.0.0/0"),
            )
        val payload =
            ImportPayloadClassifier.classify(AmneziaWgUriCodec.encode(profile)) as ImportPayload.WireGuardConf
        val editor =
            (AmneziaWgEditorState.initial().importConf(payload.conf, AwgTestCatalog) as AwgConfImport.Imported).editor
        val request = editor.toActivationRequest("ipv6-peer")

        assertTrue(payload.conf.contains("Endpoint = [2001:db8::9]:51820\n"))
        assertEquals(profile.host, request.endpointHost)
        assertEquals(profile.port, request.endpointPort)
        request.requireRuntimeReady()
    }

    @Test
    fun `importConf with non-matching params lands on custom and stays unlocked`() {
        val conf =
            """
            [Interface]
            PrivateKey = privkey==
            Jc = 9
            Jmin = 1
            Jmax = 2
            S1 = 3
            S2 = 4
            H1 = 5
            H2 = 6
            H3 = 7
            H4 = 8

            [Peer]
            PublicKey = peerpub==
            Endpoint = host.example.com:443
            """.trimIndent()

        val state = (AmneziaWgEditorState.initial().importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor

        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, state.form.cohortId)
        assertFalse(state.obfuscationLocked)
        assertEquals(9, state.form.jc)
    }

    @Test
    fun `importConf rejects malformed input`() {
        val result = AmneziaWgEditorState.initial().importConf("this is not a wg conf", AwgTestCatalog)

        assertTrue(result is AwgConfImport.Failed)
    }

    @Test
    fun `an AmneziaVPN 5x conf with S1-S4 = 12 imports as activatable`() {
        val padded =
            """
            [Interface]
            PrivateKey = $AwgPrivateTestKey
            Address = 10.8.1.2/32
            S1 = 12
            S2 = 12
            S3 = 12
            S4 = 12

            [Peer]
            PublicKey = $AwgPeerTestKey
            Endpoint = host.example.com:443
            AllowedIPs = 0.0.0.0/0
            """.trimIndent()

        val result = AmneziaWgEditorState.initial().importConf(padded, AwgTestCatalog) as AwgConfImport.Imported

        assertEquals(12 to 12, result.editor.form.s3 to result.editor.form.s4)
        assertEquals("host.example.com", result.editor.form.server)
        assertTrue(result.editor.isActivatable())
    }

    @Test
    fun `an unknown key is rejected without retaining its name or value`() {
        val conf = "[Interface]\nPrivateKey = k==\nMadeUpKey = secret-value\n\n[Peer]\nPublicKey = p=="

        val result = AmneziaWgEditorState.initial().importConf(conf, AwgTestCatalog)

        assertEquals(AwgConfImport.Failed(AwgConfImportFailure.UNSUPPORTED_OPTION), result)
        assertTrue("MadeUpKey" !in result.toString())
        assertTrue("secret-value" !in result.toString())
    }

    @Test
    fun `conf import applies the UTF-8 byte limit to pasted text`() {
        val conf = "[Interface]\nPrivateKey = test\n# " + "é".repeat(32 * 1024)

        val result = AmneziaWgEditorState.initial().importConf(conf, AwgTestCatalog)

        assertEquals(AwgConfImport.Failed(AwgConfImportFailure.UNREADABLE), result)
    }

    @Test
    fun `awg 3x keys and a keepalive range reach the activation request`() {
        val conf =
            """
            [Interface]
            PrivateKey = k==
            Address = 10.8.0.2/32
            Jc = 4
            Jmin = 10
            Jmax = 50
            RandomTrailers = on
            ContentPaddingAddition = 10-100

            [Peer]
            PublicKey = p==
            Endpoint = vpn.example.com:51820
            PersistentKeepalive = 25-35
            """.trimIndent()

        val editor = (AmneziaWgEditorState.initial().importConf(conf, AwgTestCatalog) as AwgConfImport.Imported).editor
        val request = editor.toActivationRequest(profileId = "p")

        assertTrue(editor.form.v3.randomTrailers)
        assertEquals("10-100", editor.form.v3.contentPaddingAddition)
        assertEquals(25, request.persistentKeepalive)
        assertEquals(35, request.persistentKeepaliveMax)
    }

    @Test
    fun `the conf Endpoint requires IPv6 brackets and rejects bracketed hostnames`() {
        for (endpoint in listOf("2001:db8::1:51820", "[vpn.example.com]:51820")) {
            val conf =
                """
                [Interface]
                PrivateKey = $AwgPrivateTestKey
                Address = 10.8.0.2/32
                [Peer]
                PublicKey = $AwgPeerTestKey
                Endpoint = $endpoint
                AllowedIPs = 0.0.0.0/0
                """.trimIndent()
            val editor =
                (
                    AmneziaWgEditorState.initial().importConf(
                        conf,
                        AwgTestCatalog,
                    ) as AwgConfImport.Imported
                ).editor

            assertFalse(editor.isActivatable())
        }
    }
}
