package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AmneziaWgProfileViewModel]: the AmneziaWG profile-editor backing ViewModel.
 *
 * The editor surfaces every obfuscation field inline. A cohort preset fills and locks the
 * obfuscation group; "Custom" frees it. These tests inject a fake catalog so they stay
 * pure-JVM (no Android asset loading).
 */
class AmneziaWgProfileViewModelTest {
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

    private fun viewModel() = AmneziaWgProfileViewModel(FakeCatalogProvider(catalog))

    @Test
    fun `initial state is a custom, unlocked editor`() {
        val state = viewModel().uiState.value

        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, state.editor.form.cohortId)
        assertFalse(state.editor.obfuscationLocked)
    }

    @Test
    fun `the cohort picker exposes every catalog preset plus the custom sentinel`() {
        val state = viewModel().uiState.value

        val ids = state.cohortOptions.map { it.id }
        assertTrue(ids.contains("rtk_south"))
        assertTrue(ids.contains(AwgProfileForm.CUSTOM_COHORT_ID))
    }

    @Test
    fun `selecting a cohort preset fills and locks the obfuscation fields`() {
        val viewModel = viewModel()

        viewModel.onCohortSelected("rtk_south")

        val state = viewModel.uiState.value
        assertTrue(state.editor.obfuscationLocked)
        assertEquals("rtk_south", state.editor.form.cohortId)
        assertEquals(4, state.editor.form.jc)
        assertEquals("70", state.editor.rawText(AwgEditorField.JMAX))
    }

    @Test
    fun `selecting the custom sentinel frees the obfuscation fields`() {
        val viewModel = viewModel()
        viewModel.onCohortSelected("rtk_south")

        viewModel.onCohortSelected(AwgProfileForm.CUSTOM_COHORT_ID)

        assertFalse(viewModel.uiState.value.editor.obfuscationLocked)
    }

    @Test
    fun `editing an identity field updates the form`() {
        val viewModel = viewModel()

        viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")

        assertEquals("vpn.example.com", viewModel.uiState.value.editor.form.server)
    }

    @Test
    fun `editing an obfuscation field while a preset is locked is a no-op`() {
        val viewModel = viewModel()
        viewModel.onCohortSelected("rtk_south")

        viewModel.onFieldChanged(AwgEditorField.JC, "999")

        assertEquals(4, viewModel.uiState.value.editor.form.jc)
    }

    @Test
    fun `invalid obfuscation input is flagged but does not corrupt the form`() {
        val viewModel = viewModel()

        viewModel.onFieldChanged(AwgEditorField.JC, "not-a-number")

        val state = viewModel.uiState.value
        assertTrue(state.editor.hasFieldError(AwgEditorField.JC))
        assertEquals(0, state.editor.form.jc)
    }

    @Test
    fun `pasting an awg conf populates the editor and detects the cohort`() {
        val viewModel = viewModel()
        val conf =
            """
            [Interface]
            PrivateKey = privkey==
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 50
            S2 = 100
            H1 = 1000000001
            H2 = 1000000002
            H3 = 1000000003
            H4 = 1000000004

            [Peer]
            PublicKey = peerpub==
            Endpoint = vpn.example.com:51820
            """.trimIndent()

        viewModel.onConfPasted(conf)

        val state = viewModel.uiState.value
        assertEquals("privkey==", state.editor.form.interfacePrivateKey)
        assertEquals("vpn.example.com", state.editor.form.server)
        assertEquals("rtk_south", state.editor.form.cohortId)
        assertTrue(state.editor.obfuscationLocked)
    }

    @Test
    fun `pasting a non-conf leaves the editor untouched`() {
        val viewModel = viewModel()
        viewModel.onFieldChanged(AwgEditorField.SERVER, "keep.me")

        viewModel.onConfPasted("definitely not a wireguard config")

        assertEquals("keep.me", viewModel.uiState.value.editor.form.server)
    }

    @Test
    fun `private and preshared keys start hidden behind the reveal gate`() {
        val state = viewModel().uiState.value

        assertFalse(state.privateKeyRevealed)
        assertFalse(state.presharedKeyRevealed)
    }

    @Test
    fun `the biometric reveal gate flips the private-key visibility`() {
        val viewModel = viewModel()

        viewModel.onPrivateKeyRevealAuthorized()

        assertTrue(viewModel.uiState.value.privateKeyRevealed)
    }

    @Test
    fun `the biometric reveal gate flips the preshared-key visibility`() {
        val viewModel = viewModel()

        viewModel.onPresharedKeyRevealAuthorized()

        assertTrue(viewModel.uiState.value.presharedKeyRevealed)
    }
}

/** Test double that returns a fixed catalog without touching Android assets. */
private class FakeCatalogProvider(
    private val catalog: AwgCohortCatalogData,
) : AwgCohortCatalogProvider {
    override fun catalog(): AwgCohortCatalogData = catalog
}
