package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure-logic tests for [AmneziaWgEditorState] and its field-validation helpers.
 *
 * The editor surfaces every AmneziaWG obfuscation field inline. Selecting a cohort preset
 * fills and *locks* the obfuscation group; the "Custom" sentinel frees them again. The
 * identity group (keys, server, port) is always editable and never touched by a preset.
 */
class AmneziaWgEditorStateTest {
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
            h1 = 1_000_000_001L,
            h2 = 1_000_000_002L,
            h3 = 1_000_000_003L,
            h4 = 1_000_000_004L,
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
        assertEquals(1_000_000_001L, state.form.h1)
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
    fun `s3 and s4 accept only zero`() {
        assertEquals(0, AwgEditorField.S3.validate("0"))
        assertEquals(0, AwgEditorField.S4.validate("0"))
        assertNull(AwgEditorField.S3.validate("1"))
        assertNull(AwgEditorField.S4.validate("1"))
    }

    @Test
    fun `h-field rejects a value over the 4-byte unsigned ceiling`() {
        assertNull(AwgEditorField.H1.validate("4294967296"))
        assertNull(AwgEditorField.H1.validate("-1"))
        assertEquals(4_294_967_295L, AwgEditorField.H1.validate("4294967295"))
    }

    @Test
    fun `i-field accepts only hex strings`() {
        assertNull(AwgEditorField.I1.validate("zzzz"))
        assertNull(AwgEditorField.I1.validate(""))
        assertEquals("deadbeef", AwgEditorField.I1.validate("DEADBEEF"))
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
    fun `populateFromConf fills every field from a parsed awg config`() {
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
            I1 = ab12

            [Peer]
            PublicKey = peerpub==
            Endpoint = vpn.example.com:51820
            """.trimIndent()

        val state = AmneziaWgEditorState.initial().populateFromConf(conf, catalog)

        assertEquals("privkey==", state.form.interfacePrivateKey)
        assertEquals("peerpub==", state.form.peerPublicKey)
        assertEquals("vpn.example.com", state.form.server)
        assertEquals(51_820, state.form.serverPort)
        assertEquals(4, state.form.jc)
        assertEquals("ab12", state.rawText(AwgEditorField.I1))
        // The conf's obfuscation params byte-match rtk_south, so the cohort is detected.
        assertEquals("rtk_south", state.form.cohortId)
        assertTrue(state.obfuscationLocked)
    }

    @Test
    fun `populateFromConf seeds Address, DNS, AllowedIPs, MTU and keepalive (P1-12)`() {
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

        val state = AmneziaWgEditorState.initial().populateFromConf(conf, catalog)

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
    fun `populateFromConf with non-matching params lands on custom and stays unlocked`() {
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

        val state = AmneziaWgEditorState.initial().populateFromConf(conf, catalog)

        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, state.form.cohortId)
        assertFalse(state.obfuscationLocked)
        assertEquals(9, state.form.jc)
    }

    @Test
    fun `populateFromConf on malformed input leaves the state unchanged`() {
        val before = AmneziaWgEditorState.initial().updateField(AwgEditorField.SERVER, "keep.me")

        val after = before.populateFromConf("this is not a wg conf", catalog)

        assertEquals("keep.me", after.form.server)
    }

    @Test
    fun `populateFromConf with non-zero S3 or S4 leaves the state unchanged`() {
        val before = AmneziaWgEditorState.initial().updateField(AwgEditorField.SERVER, "keep.me")
        val unsafe =
            """
            [Interface]
            PrivateKey = privkey==
            S3 = 1
            S4 = 0

            [Peer]
            PublicKey = peerpub==
            Endpoint = host.example.com:443
            """.trimIndent()

        val after = before.populateFromConf(unsafe, catalog)

        assertEquals(before, after)
    }

    @Test
    fun `a consistent identity-complete profile is activatable`() {
        assertTrue(activatableState().isActivatable())
    }

    @Test
    fun `non-zero raw S3 or S4 is invalid and blocks activation`() {
        val s3 = activatableState().updateField(AwgEditorField.S3, "1")
        val s4 = activatableState().updateField(AwgEditorField.S4, "1")

        assertTrue(s3.hasFieldError(AwgEditorField.S3))
        assertTrue(s4.hasFieldError(AwgEditorField.S4))
        assertFalse(s3.isActivatable())
        assertFalse(s4.isActivatable())
    }

    @Test
    fun `non-zero projected S3 or S4 blocks activation`() {
        val safe = activatableState()
        val s3 = safe.copy(form = safe.form.copy(s3 = 1))
        val s4 = safe.copy(form = safe.form.copy(s4 = 1))

        assertFalse(s3.obfuscationConsistent())
        assertFalse(s4.obfuscationConsistent())
        assertFalse(s3.isActivatable())
        assertFalse(s4.isActivatable())
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
    fun `an inverted junk range is tolerated when no junk packets are emitted`() {
        val state =
            activatableState()
                .updateField(AwgEditorField.JC, "0")
                .updateField(AwgEditorField.JMIN, "70")
                .updateField(AwgEditorField.JMAX, "40")

        assertTrue(state.obfuscationConsistent())
        assertTrue(state.isActivatable())
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
