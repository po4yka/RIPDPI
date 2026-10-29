package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Pure-logic tests for [AmneziaWgEditorState] and its field-validation helpers.
 *
 * The editor surfaces every AmneziaWG obfuscation field inline. Selecting a cohort preset
 * fills and *locks* the obfuscation group; the "Custom" sentinel frees them again. The
 * identity group (keys, server, port) is always editable and never touched by a preset.
 */
class AmneziaWgEditorStateTest {
    @Test
    fun `keepalive grammar blocks invalid edits and activation without coercion`() {
        val root =
            generateSequence(File(".").canonicalFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
        File(root, "core/data/src/test/resources/contract/awg-keepalive-invalid.txt").readLines().forEach { raw ->
            val editor = activatableState().updateField(AwgEditorField.PERSISTENT_KEEPALIVE, raw)
            assertEquals(raw, editor.rawText(AwgEditorField.PERSISTENT_KEEPALIVE))
            assertTrue(raw, editor.hasFieldError(AwgEditorField.PERSISTENT_KEEPALIVE))
            assertFalse(raw, editor.isActivatable())
            assertThrows(raw, IllegalArgumentException::class.java) { editor.toActivationRequest("profile") }
        }
        listOf(
            "" to (0 to 0),
            "0" to (0 to 0),
            "0-0" to (0 to 0),
            "1" to (1 to 0),
            "25-25" to (25 to 0),
            "25-35" to (25 to 35),
            "65535" to (65535 to 0),
            "1-65535" to (1 to 65535),
        ).forEach { (raw, expected) ->
            val editor = activatableState().updateField(AwgEditorField.PERSISTENT_KEEPALIVE, raw)
            assertFalse(raw, editor.hasFieldError(AwgEditorField.PERSISTENT_KEEPALIVE))
            assertTrue(raw, editor.isActivatable())
            val request = editor.toActivationRequest("profile")
            assertEquals(raw, expected, request.persistentKeepalive to request.persistentKeepaliveMax)
        }
    }

    @Test
    fun `importing absent or off keepalive replaces an earlier invalid or positive value`() {
        for (previous in listOf("0-1", "25-35")) {
            for (line in listOf("", "PersistentKeepalive = off")) {
                val conf =
                    "[Interface]\nPrivateKey = k==\nAddress = 10.8.0.2/32\n" +
                        "[Peer]\nPublicKey = p==\nEndpoint = vpn.example.com:51820\n$line"
                val edited = activatableState().updateField(AwgEditorField.PERSISTENT_KEEPALIVE, previous)
                val imported = (edited.importConf(conf, catalog) as AwgConfImport.Imported).editor
                assertEquals("", imported.rawText(AwgEditorField.PERSISTENT_KEEPALIVE))
                assertTrue(imported.isActivatable())
                val request = imported.toActivationRequest("profile")
                assertEquals(0 to 0, request.persistentKeepalive to request.persistentKeepaliveMax)
            }
        }
    }

    @Test
    fun `header protection requires all four nonce prefixes before readiness and save`() {
        val key = AwgHeaderProtectionKey(Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
        val fields = listOf(AwgEditorField.S1, AwgEditorField.S2, AwgEditorField.S3, AwgEditorField.S4)
        val initial = activatableState()
        val protected =
            fields.fold(initial.copy(form = initial.form.copy(v3 = AmneziaWgV3Parameters(headerProtectionKey = key)))) {
                editor,
                field,
                ->
                editor.updateField(field, "12")
            }
        assertTrue(protected.isActivatable())
        fields.forEach { field ->
            for (size in listOf("", "0", "11")) {
                val invalid = protected.updateField(field, size)
                assertTrue(field.name, invalid.hasFieldError(field))
                assertFalse(field.name, invalid.isActivatable())
                val error =
                    assertThrows(IllegalArgumentException::class.java) { invalid.toActivationRequest("profile") }
                assertFalse(error.message.orEmpty().contains(key.value))
            }
            val valid = protected.updateField(field, "13")
            assertFalse(valid.hasFieldError(field))
            assertTrue(valid.isActivatable())
            assertEquals(
                key,
                valid
                    .toActivationRequest("profile")
                    .obfuscation.v3.headerProtectionKey,
            )
        }
        val zero = AwgHeaderProtectionKey(Base64.getEncoder().encodeToString(ByteArray(32)))
        val disabled = initial.copy(form = initial.form.copy(v3 = AmneziaWgV3Parameters(headerProtectionKey = zero)))
        assertTrue(disabled.isActivatable())
        assertTrue(fields.none(disabled::hasFieldError))
    }

    @Test
    fun `all v3 fields edit and reach activation while invalid drafts stay visible`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val values =
            listOf(
                AwgEditorField.HEADER_PROTECTION_KEY to key,
                AwgEditorField.CONTENT_PADDING_ADDITION to "10-100",
                AwgEditorField.REKEY_AFTER_TIME to "100-120",
                AwgEditorField.REKEY_TIMEOUT to "3-7",
                AwgEditorField.REJECT_AFTER_TIME to "150-180",
                AwgEditorField.KEEPALIVE_TIMEOUT to "5-15",
                AwgEditorField.MAX_HANDSHAKE_ATTEMPTS to "15-20",
                AwgEditorField.RANDOM_TRAILERS to "true",
                AwgEditorField.DISABLE_COOKIES to "true",
            )
        val prefixes =
            listOf(AwgEditorField.S1, AwgEditorField.S2, AwgEditorField.S3, AwgEditorField.S4)
                .fold(activatableState()) { state, field -> state.updateField(field, "12") }
        val editor = values.fold(prefixes) { state, (field, value) -> state.updateField(field, value) }
        val expected =
            AmneziaWgV3Parameters(
                headerProtectionKey = AwgHeaderProtectionKey(key),
                contentPaddingAddition = "10-100",
                rekeyAfterTime = "100-120",
                rekeyTimeout = "3-7",
                rejectAfterTime = "150-180",
                keepaliveTimeout = "5-15",
                maxHandshakeAttempts = "15-20",
                randomTrailers = true,
                disableCookies = true,
            )
        assertTrue(editor.isActivatable())
        assertEquals(expected, editor.toActivationRequest("profile").obfuscation.v3)
        values.forEach { (field, value) -> assertEquals(value, editor.rawText(field)) }
        assertFalse(editor.toString().contains(key))
        listOf(
            AwgEditorField.HEADER_PROTECTION_KEY to "invalid-fixture-key",
            AwgEditorField.REKEY_TIMEOUT to "1-2-3",
        ).forEach { (field, value) ->
            val invalid = editor.updateField(field, value)
            assertEquals(value, invalid.rawText(field))
            assertTrue(invalid.hasFieldError(field))
            assertFalse(invalid.isActivatable())
            assertThrows(IllegalArgumentException::class.java) { invalid.toActivationRequest("profile") }
        }
        val cleared =
            values.fold(editor) { state, (field, _) ->
                state.updateField(
                    field,
                    if (field == AwgEditorField.RANDOM_TRAILERS ||
                        field == AwgEditorField.DISABLE_COOKIES
                    ) {
                        "false"
                    } else {
                        ""
                    },
                )
            }
        assertEquals(AmneziaWgV3Parameters(), cleared.form.v3)
        assertFalse(cleared.hasV3Edits())
    }

    @Test
    fun `preset replacement clears absent signature text as well as form values`() {
        val editor = activatableState().updateField(AwgEditorField.I1, "<r 3>").selectCohort(rtkSouth)
        assertEquals("", editor.form.i1)
        assertEquals("", editor.rawText(AwgEditorField.I1))
    }

    @Test
    fun `editor diagnostics do not include raw credentials`() {
        val editor = activatableState().updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, "private-fixture-value")
        assertFalse(editor.toString().contains("private-fixture-value"))
    }

    private val rtkSouth =
        AwgCohortPreset(
            id = "rtk_south",
            displayNameKey = "awg_cohort_rtk_south_name",
            descriptionKey = "awg_cohort_rtk_south_desc",
            jc = 4,
            jmin = 40,
            jmax = 70,
            s1 = 50,
            s2 = 100,
            h1 = "1000000001",
            h2 = "1000000002",
            h3 = "1000000003",
            h4 = "1000000004",
            randomizeHeaders = false,
        )
    private val catalog = AwgCohortCatalogData(presets = listOf(rtkSouth))

    @Test
    fun `blank AllowedIPs defaults to routes for configured interface families`() {
        val ipv4 = AmneziaWgEditorState.initial().updateField(AwgEditorField.ADDRESS, "10.8.0.2/32")
        val dualStack = ipv4.updateField(AwgEditorField.ADDRESS, "fd00::2/128, 10.8.0.2/32")

        assertEquals(listOf("0.0.0.0/0"), ipv4.toActivationRequest("awg-v4").allowedIps)
        assertEquals(listOf("0.0.0.0/0", "::/0"), dualStack.toActivationRequest("awg-dual").allowedIps)
    }

    @Test
    fun `initial state is custom with an unlocked obfuscation group`() {
        val state = AmneziaWgEditorState.initial()

        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, state.form.cohortId)
        assertFalse(state.obfuscationLocked)
    }

    @Test
    fun `selecting a cohort preset fills and locks the obfuscation fields`() {
        val state = AmneziaWgEditorState.initial().selectCohort(rtkSouth)

        assertTrue(state.obfuscationLocked)
        assertEquals("rtk_south", state.form.cohortId)
        assertEquals(4, state.form.jc)
        assertEquals(70, state.form.jmax)
        assertEquals("1000000001", state.form.h1)
    }

    @Test
    fun `selecting a preset preserves the identity group`() {
        val withIdentity =
            AmneziaWgEditorState
                .initial()
                .updateField(
                    AwgEditorField.SERVER,
                    "vpn.example.com",
                ).updateField(AwgEditorField.SERVER_PORT, "51820")
                .updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, "privkey==")

        val afterPreset = withIdentity.selectCohort(rtkSouth)

        assertEquals("vpn.example.com", afterPreset.form.server)
        assertEquals(51_820, afterPreset.form.serverPort)
        assertEquals("privkey==", afterPreset.form.interfacePrivateKey)
    }

    @Test
    fun `choosing custom frees the obfuscation fields again`() {
        val locked = AmneziaWgEditorState.initial().selectCohort(rtkSouth)

        val freed = locked.selectCustom()

        assertFalse(freed.obfuscationLocked)
        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, freed.form.cohortId)
        // The numeric values stay (so the user can tweak from a known-good baseline).
        assertEquals(4, freed.form.jc)
    }

    @Test
    fun `editing an obfuscation field while locked is a no-op`() {
        val locked = AmneziaWgEditorState.initial().selectCohort(rtkSouth)

        val attempted = locked.updateField(AwgEditorField.JC, "999")

        assertEquals(4, attempted.form.jc)
    }

    @Test
    fun `editing an obfuscation field while unlocked applies`() {
        val state = AmneziaWgEditorState.initial().updateField(AwgEditorField.JC, "7")

        assertEquals(7, state.form.jc)
    }

    @Test
    fun `jc field rejects a negative integer`() {
        assertNull(AwgEditorField.JC.validate("-1"))
        assertNull(AwgEditorField.JC.validate("not-a-number"))
        assertEquals(3, AwgEditorField.JC.validate("3"))
    }

    @Test
    fun `s3 and s4 accept non-negative padding up to the maximum packet`() {
        assertEquals(0, AwgEditorField.S3.validate("0"))
        assertEquals(12, AwgEditorField.S3.validate("12"))
        assertEquals(12, AwgEditorField.S4.validate("12"))
        assertNull(AwgEditorField.S3.validate("-1"))
        assertNull(AwgEditorField.S4.validate("-1"))
        assertNull(AwgEditorField.S4.validate((AmneziaWgWireProfile.MaximumPacket + 1).toString()))
    }

    @Test
    fun `h-field rejects a value over the 4-byte unsigned ceiling`() {
        assertNull(AwgEditorField.H1.validate("4294967296"))
        assertNull(AwgEditorField.H1.validate("-1"))
        assertEquals("4294967295", AwgEditorField.H1.validate("4294967295"))
        assertEquals("100-199", AwgEditorField.H1.validate("100-199"))
    }

    @Test
    fun `i-field accepts bounded signature programs and an unused slot`() {
        assertNull(AwgEditorField.I1.validate("zzzz"))
        assertEquals("", AwgEditorField.I1.validate(""))
        assertNull(AwgEditorField.I1.validate("DEADBEEF"))
        assertEquals("<b 0xdeadbeef><r 4>", AwgEditorField.I1.validate("<b 0xdeadbeef><r 4>"))
    }

    @Test
    fun `editing an i-field is reflected in the raw text map`() {
        val state = AmneziaWgEditorState.initial().updateField(AwgEditorField.I1, "ab12")

        assertEquals("ab12", state.rawText(AwgEditorField.I1))
    }

    @Test
    fun `invalid raw text is retained but does not corrupt the form`() {
        val state = AmneziaWgEditorState.initial().updateField(AwgEditorField.JMIN, "bad")

        assertEquals("bad", state.rawText(AwgEditorField.JMIN))
        assertEquals(0, state.form.jmin)
        assertTrue(state.hasFieldError(AwgEditorField.JMIN))
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

        val state = (AmneziaWgEditorState.initial().importConf(conf, catalog) as AwgConfImport.Imported).editor

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

        val state = (AmneziaWgEditorState.initial().importConf(conf, catalog) as AwgConfImport.Imported).editor

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

        val state = (AmneziaWgEditorState.initial().importConf(conf, catalog) as AwgConfImport.Imported).editor

        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, state.form.cohortId)
        assertFalse(state.obfuscationLocked)
        assertEquals(9, state.form.jc)
    }

    @Test
    fun `importConf rejects malformed input`() {
        val result = AmneziaWgEditorState.initial().importConf("this is not a wg conf", catalog)

        assertTrue(result is AwgConfImport.Failed)
    }

    @Test
    fun `an AmneziaVPN 5x conf with S1-S4 = 12 imports as activatable`() {
        val padded =
            """
            [Interface]
            PrivateKey = privkey==
            Address = 10.8.1.2/32
            S1 = 12
            S2 = 12
            S3 = 12
            S4 = 12

            [Peer]
            PublicKey = peerpub==
            Endpoint = host.example.com:443
            """.trimIndent()

        val result = AmneziaWgEditorState.initial().importConf(padded, catalog) as AwgConfImport.Imported

        assertEquals(12 to 12, result.editor.form.s3 to result.editor.form.s4)
        assertEquals("host.example.com", result.editor.form.server)
        assertTrue(result.editor.isActivatable())
    }

    @Test
    fun `an unknown key is named without its value`() {
        val conf = "[Interface]\nPrivateKey = k==\nMadeUpKey = secret-value\n\n[Peer]\nPublicKey = p=="

        val result = AmneziaWgEditorState.initial().importConf(conf, catalog)

        assertEquals(AwgConfImport.Failed(AwgConfImportFailure.UNSUPPORTED_OPTION, "MadeUpKey"), result)
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

        val editor = (AmneziaWgEditorState.initial().importConf(conf, catalog) as AwgConfImport.Imported).editor
        val request = editor.toActivationRequest(profileId = "p")

        assertTrue(editor.form.v3.randomTrailers)
        assertEquals("10-100", editor.form.v3.contentPaddingAddition)
        assertEquals(25, request.persistentKeepalive)
        assertEquals(35, request.persistentKeepaliveMax)
    }

    @Test
    fun `a consistent identity-complete profile is activatable`() {
        assertTrue(activatableState().isActivatable())
    }

    @Test
    fun `raw AmneziaVPN 5x S3 and S4 stay activatable and a negative value blocks activation`() {
        val padded = activatableState().updateField(AwgEditorField.S3, "12").updateField(AwgEditorField.S4, "12")
        val s3 = activatableState().updateField(AwgEditorField.S3, "-1")
        val s4 = activatableState().updateField(AwgEditorField.S4, "-1")

        assertFalse(padded.hasFieldError(AwgEditorField.S3))
        assertFalse(padded.hasFieldError(AwgEditorField.S4))
        assertTrue(padded.isActivatable())
        assertTrue(s3.hasFieldError(AwgEditorField.S3))
        assertTrue(s4.hasFieldError(AwgEditorField.S4))
        assertFalse(s3.isActivatable())
        assertFalse(s4.isActivatable())
    }

    @Test
    fun `projected S3 or S4 blocks activation only when it exceeds the MTU`() {
        val safe = activatableState().updateField(AwgEditorField.MTU, "1280")
        val padded = safe.copy(form = safe.form.copy(s3 = 12, s4 = 12))
        val s3 = safe.copy(form = safe.form.copy(s3 = 1281))
        val s4 = safe.copy(form = safe.form.copy(s4 = 1281))

        assertTrue(padded.obfuscationConsistent())
        assertTrue(padded.isActivatable())
        assertFalse(s3.obfuscationConsistent())
        assertFalse(s4.obfuscationConsistent())
        assertFalse(s3.isActivatable())
        assertFalse(s4.isActivatable())
    }

    @Test
    fun `projected negative S3 or S4 blocks activation`() {
        val safe = activatableState()

        listOf(safe.form.copy(s3 = -1), safe.form.copy(s4 = -1)).forEach { form ->
            val state = safe.copy(form = form)
            assertFalse(state.obfuscationConsistent())
            assertFalse(state.isActivatable())
        }
    }

    @Test
    fun `an inverted junk range with junk active blocks activation`() {
        val state =
            activatableState()
                .updateField(AwgEditorField.JC, "4")
                .updateField(AwgEditorField.JMIN, "70")
                .updateField(AwgEditorField.JMAX, "40")

        assertFalse(state.obfuscationConsistent())
        assertFalse(state.isActivatable())
    }

    @Test
    fun `an inverted junk range blocks activation even when no junk packets are emitted`() {
        val state =
            activatableState()
                .updateField(AwgEditorField.JC, "0")
                .updateField(AwgEditorField.JMIN, "70")
                .updateField(AwgEditorField.JMAX, "40")

        assertFalse(state.obfuscationConsistent())
        assertFalse(state.isActivatable())
    }

    @Test
    fun `a junk size above the configured MTU blocks activation`() {
        val state =
            activatableState()
                .updateField(AwgEditorField.MTU, "1280")
                .updateField(AwgEditorField.JC, "4")
                .updateField(AwgEditorField.JMIN, "40")
                .updateField(AwgEditorField.JMAX, "2000")

        assertFalse(state.isActivatable())
    }

    private fun activatableState(): AmneziaWgEditorState =
        AmneziaWgEditorState
            .initial()
            .updateField(AwgEditorField.SERVER, "vpn.example.com")
            .updateField(AwgEditorField.SERVER_PORT, "51820")
            .updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, "privkey==")
            .updateField(AwgEditorField.PEER_PUBLIC_KEY, "peerpub==")
            .updateField(AwgEditorField.ADDRESS, "10.8.0.2/32")
}
