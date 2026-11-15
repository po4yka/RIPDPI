package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Tests for field validation, activation projection, and cohort selection. */
class AmneziaWgEditorStateTest {
    @Test
    fun `WS activation requires a valid WSS endpoint`() {
        val state = activatableAwgEditor().selectCarrier(AwgActivationRequest.CARRIER_WS)
        listOf(
            "",
            "ws://vpn.example.com/wg",
            "wss://user:secret@vpn.example.com/wg",
            "wss://vpn.example.com/wg#fragment",
        ).forEach { url ->
            assertFalse(state.updateField(AwgEditorField.CARRIER_WS_URL, url).isActivatable())
        }
        listOf("wss://vpn.example.com:8443/wg?profile=a", "wss://[2001:db8::1]/wg").forEach { url ->
            assertTrue(state.updateField(AwgEditorField.CARRIER_WS_URL, url).isActivatable())
        }
    }

    @Test
    fun `keepalive grammar blocks invalid edits and activation without coercion`() {
        val root =
            generateSequence(File(".").canonicalFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
        File(root, "core/data/src/test/resources/awg-keepalive-invalid.txt").readLines().forEach { raw ->
            val editor = activatableAwgEditor().updateField(AwgEditorField.PERSISTENT_KEEPALIVE, raw)
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
            val editor = activatableAwgEditor().updateField(AwgEditorField.PERSISTENT_KEEPALIVE, raw)
            assertFalse(raw, editor.hasFieldError(AwgEditorField.PERSISTENT_KEEPALIVE))
            assertTrue(raw, editor.isActivatable())
            val request = editor.toActivationRequest("profile")
            assertEquals(raw, expected, request.persistentKeepalive to request.persistentKeepaliveMax)
        }
    }

    @Test
    fun `header protection requires all four nonce prefixes before readiness and save`() {
        val key = AwgHeaderProtectionKey(Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
        val fields = listOf(AwgEditorField.S1, AwgEditorField.S2, AwgEditorField.S3, AwgEditorField.S4)
        val initial = activatableAwgEditor()
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
                .fold(activatableAwgEditor()) { state, field -> state.updateField(field, "12") }
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
        val editor = activatableAwgEditor().updateField(AwgEditorField.I1, "<r 3>").selectCohort(RtkSouthTestPreset)
        assertEquals("", editor.form.i1)
        assertEquals("", editor.rawText(AwgEditorField.I1))
    }

    @Test
    fun `editor diagnostics do not include raw credentials`() {
        val editor = activatableAwgEditor().updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, "private-fixture-value")
        assertFalse(editor.toString().contains("private-fixture-value"))
    }

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
        val state = AmneziaWgEditorState.initial().selectCohort(RtkSouthTestPreset)

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

        val afterPreset = withIdentity.selectCohort(RtkSouthTestPreset)

        assertEquals("vpn.example.com", afterPreset.form.server)
        assertEquals(51_820, afterPreset.form.serverPort)
        assertEquals("privkey==", afterPreset.form.interfacePrivateKey)
    }

    @Test
    fun `choosing custom frees the obfuscation fields again`() {
        val locked = AmneziaWgEditorState.initial().selectCohort(RtkSouthTestPreset)

        val freed = locked.selectCustom()

        assertFalse(freed.obfuscationLocked)
        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, freed.form.cohortId)
        // The numeric values stay (so the user can tweak from a known-good baseline).
        assertEquals(4, freed.form.jc)
    }

    @Test
    fun `editing an obfuscation field while locked is a no-op`() {
        val locked = AmneziaWgEditorState.initial().selectCohort(RtkSouthTestPreset)

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
    fun `invalid activation input marks the field and disables Connect`() {
        val cases =
            listOf(
                AwgEditorField.INTERFACE_PRIVATE_KEY to "invalid-private-key",
                AwgEditorField.PEER_PUBLIC_KEY to "invalid-public-key",
                AwgEditorField.PRESHARED_KEY to "invalid-preshared-key",
                AwgEditorField.SERVER to "vpn.example.com/path",
                AwgEditorField.SERVER_PORT to "65536",
                AwgEditorField.SERVER_PORT to "invalid-port",
                AwgEditorField.ADDRESS to "10.8.0.2",
                AwgEditorField.DNS to "vpn.example.com",
                AwgEditorField.MTU to "0",
                AwgEditorField.MTU to "invalid-mtu",
                AwgEditorField.ALLOWED_IPS to "10.8.0.0/99",
            )
        for ((field, value) in cases) {
            val editor = activatableAwgEditor().updateField(field, value)
            assertTrue(field.name, editor.hasFieldError(field))
            assertFalse(field.name, editor.isActivatable())
        }
    }

    @Test
    fun `invalid MTU is highlighted even when it is smaller than the padding`() {
        val editor = activatableAwgEditor().updateField(AwgEditorField.S1, "12").updateField(AwgEditorField.MTU, "1")

        assertTrue(editor.hasFieldError(AwgEditorField.MTU))
        assertFalse(editor.isActivatable())
    }

    @Test
    fun `a consistent identity-complete profile is activatable`() {
        assertTrue(activatableAwgEditor().isActivatable())
    }

    @Test
    fun `raw AmneziaVPN 5x S3 and S4 stay activatable and a negative value blocks activation`() {
        val padded = activatableAwgEditor().updateField(AwgEditorField.S3, "12").updateField(AwgEditorField.S4, "12")
        val s3 = activatableAwgEditor().updateField(AwgEditorField.S3, "-1")
        val s4 = activatableAwgEditor().updateField(AwgEditorField.S4, "-1")

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
        val safe = activatableAwgEditor().updateField(AwgEditorField.MTU, "1280")
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
        val safe = activatableAwgEditor()

        listOf(safe.form.copy(s3 = -1), safe.form.copy(s4 = -1)).forEach { form ->
            val state = safe.copy(form = form)
            assertFalse(state.obfuscationConsistent())
            assertFalse(state.isActivatable())
        }
    }

    @Test
    fun `an inverted junk range with junk active blocks activation`() {
        val state =
            activatableAwgEditor()
                .updateField(AwgEditorField.JC, "4")
                .updateField(AwgEditorField.JMIN, "70")
                .updateField(AwgEditorField.JMAX, "40")

        assertFalse(state.obfuscationConsistent())
        assertFalse(state.isActivatable())
    }

    @Test
    fun `an inverted junk range blocks activation even when no junk packets are emitted`() {
        val state =
            activatableAwgEditor()
                .updateField(AwgEditorField.JC, "0")
                .updateField(AwgEditorField.JMIN, "70")
                .updateField(AwgEditorField.JMAX, "40")

        assertFalse(state.obfuscationConsistent())
        assertFalse(state.isActivatable())
    }

    @Test
    fun `a junk size above the configured MTU blocks activation`() {
        val state =
            activatableAwgEditor()
                .updateField(AwgEditorField.MTU, "1280")
                .updateField(AwgEditorField.JC, "4")
                .updateField(AwgEditorField.JMIN, "40")
                .updateField(AwgEditorField.JMAX, "2000")

        assertFalse(state.isActivatable())
    }
}
