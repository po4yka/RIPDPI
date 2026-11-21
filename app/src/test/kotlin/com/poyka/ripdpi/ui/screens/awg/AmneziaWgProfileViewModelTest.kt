package com.poyka.ripdpi.ui.screens.awg

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Tests for editor state, import, cohort selection, and secret reveal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmneziaWgProfileViewModelTest {
    private val fixture = AwgProfileViewModelFixture()

    @get:Rule
    val dispatcherRule: TestRule = fixture

    @Test
    fun `initial state is a custom, unlocked editor`() {
        val state = fixture.viewModel().uiState.value

        assertEquals(AwgProfileForm.CUSTOM_COHORT_ID, state.editor.form.cohortId)
        assertFalse(state.editor.obfuscationLocked)
    }

    @Test
    fun `the cohort picker exposes every catalog preset plus the custom sentinel`() {
        val state = fixture.viewModel().uiState.value

        val ids = state.cohortOptions.map { it.id }
        assertTrue(ids.contains("rtk_south"))
        assertTrue(ids.contains(AwgProfileForm.CUSTOM_COHORT_ID))
    }

    @Test
    fun `selecting a cohort preset fills and locks the obfuscation fields`() {
        val viewModel = fixture.viewModel()

        viewModel.onCohortSelected("rtk_south")

        val state = viewModel.uiState.value
        assertTrue(state.editor.obfuscationLocked)
        assertEquals("rtk_south", state.editor.form.cohortId)
        assertEquals(4, state.editor.form.jc)
        assertEquals("70", state.editor.rawText(AwgEditorField.JMAX))
    }

    @Test
    fun `selecting the custom sentinel frees the obfuscation fields`() {
        val viewModel = fixture.viewModel()
        viewModel.onCohortSelected("rtk_south")

        viewModel.onCohortSelected(AwgProfileForm.CUSTOM_COHORT_ID)

        assertFalse(viewModel.uiState.value.editor.obfuscationLocked)
    }

    @Test
    fun `editing an identity field updates the form`() {
        val viewModel = fixture.viewModel()

        viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")

        assertEquals("vpn.example.com", viewModel.uiState.value.editor.form.server)
    }

    @Test
    fun `editing an obfuscation field while a preset is locked is a no-op`() {
        val viewModel = fixture.viewModel()
        viewModel.onCohortSelected("rtk_south")

        viewModel.onFieldChanged(AwgEditorField.JC, "999")

        assertEquals(4, viewModel.uiState.value.editor.form.jc)
    }

    @Test
    fun `invalid obfuscation input is flagged but does not corrupt the form`() {
        val viewModel = fixture.viewModel()

        viewModel.onFieldChanged(AwgEditorField.JC, "not-a-number")

        val state = viewModel.uiState.value
        assertTrue(state.editor.hasFieldError(AwgEditorField.JC))
        assertEquals(0, state.editor.form.jc)
    }

    @Test
    fun `pasting an awg conf populates the editor and detects the cohort`() {
        val viewModel = fixture.viewModel()
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
        val viewModel = fixture.viewModel()
        viewModel.onFieldChanged(AwgEditorField.SERVER, "keep.me")

        viewModel.onConfPasted("definitely not a wireguard config")

        assertEquals("keep.me", viewModel.uiState.value.editor.form.server)
        assertEquals(
            AwgConfImportFailure.INVALID,
            viewModel.uiState.value.confImportFailure
                ?.reason,
        )
        viewModel.onFieldChanged(AwgEditorField.SERVER, "edited")
        assertNull(viewModel.uiState.value.confImportFailure)
    }

    @Test
    fun `a staged conf token prefills the editor once`() {
        val viewModel = fixture.viewModel()
        val store = PendingProxyImportStore()
        val conf = "[Interface]\nPrivateKey = k==\nJc = 4\n\n[Peer]\nPublicKey = p==\nEndpoint = vpn.example.com:51820"
        val token = checkNotNull(store.stageAmneziaWgConf(conf))

        viewModel.onImportToken(token, store)
        viewModel.onFieldChanged(AwgEditorField.SERVER, "edited")
        viewModel.onImportToken(token, store)

        assertEquals("edited", viewModel.uiState.value.editor.form.server)
        assertEquals("k==", viewModel.uiState.value.editor.form.interfacePrivateKey)
        assertNull(viewModel.uiState.value.confImportFailure)
    }

    @Test
    fun `a conf token lost with the process leaves the editor without a parse error`() {
        val viewModel = fixture.viewModel()

        viewModel.onImportToken(UUID.randomUUID().toString(), PendingProxyImportStore())

        assertNull(viewModel.uiState.value.confImportFailure)
    }

    @Test
    fun `a plain wireguard conf imports as an unobfuscated profile`() {
        val viewModel = fixture.viewModel()

        viewModel.onConfPasted(
            "[Interface]\nPrivateKey = k==\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = p==\n" +
                "Endpoint = [2001:db8::1]:51820\nAllowedIPs = 0.0.0.0/0",
        )

        val form = viewModel.uiState.value.editor.form
        assertNull(viewModel.uiState.value.confImportFailure)
        assertEquals("2001:db8::1", form.server)
        assertEquals(51820, form.serverPort)
        assertEquals(listOf(0, 0, 0), listOf(form.jc, form.s1, form.s2))
        assertEquals(listOf("1", "2", "3", "4"), listOf(form.h1, form.h2, form.h3, form.h4))
    }

    @Test
    fun `a fixed listen port is reported without modifying the editor`() {
        val viewModel = fixture.viewModel()
        viewModel.onFieldChanged(AwgEditorField.SERVER, "keep.me")
        val conf =
            "[Interface]\nPrivateKey = k==\nListenPort = 51820\n\n[Peer]\n" +
                "PublicKey = p==\nEndpoint = vpn.example.com:51820"

        viewModel.onConfPasted(conf)

        assertEquals("keep.me", viewModel.uiState.value.editor.form.server)
        assertEquals(
            AwgConfImport.Failed(AwgConfImportFailure.UNSUPPORTED_LISTEN_PORT),
            viewModel.uiState.value.confImportFailure,
        )

        viewModel.onConfPasted(conf.replace("ListenPort = 51820", "ListenPort = 0"))

        assertEquals("vpn.example.com", viewModel.uiState.value.editor.form.server)
        assertNull(viewModel.uiState.value.confImportFailure)
    }

    @Test
    fun `extra peers are reported and only the first peer is used`() {
        val viewModel = fixture.viewModel()

        viewModel.onConfPasted(
            "[Interface]\nPrivateKey = k==\n\n[Peer]\nPublicKey = first==\nEndpoint = a.example:1\n\n" +
                "[Peer]\nPublicKey = second==\nEndpoint = b.example:2\n\n[Peer]\nPublicKey = third==\n",
        )

        assertEquals("first==", viewModel.uiState.value.editor.form.peerPublicKey)
        assertEquals(2, viewModel.uiState.value.ignoredPeerCount)
    }

    @Test
    fun `an unreadable file is reported apart from an invalid config`() {
        val viewModel = fixture.viewModel()

        viewModel.onConfPasted("")

        assertEquals(
            AwgConfImportFailure.UNREADABLE,
            viewModel.uiState.value.confImportFailure
                ?.reason,
        )
    }

    @Test
    fun `private and preshared keys start hidden behind the reveal gate`() {
        val state = fixture.viewModel().uiState.value

        assertFalse(state.privateKeyRevealed)
        assertFalse(state.presharedKeyRevealed)
    }

    @Test
    fun `the biometric reveal gate flips the private-key visibility`() {
        val viewModel = fixture.viewModel()

        viewModel.onPrivateKeyRevealAuthorized()

        assertTrue(viewModel.uiState.value.privateKeyRevealed)
    }

    @Test
    fun `the biometric reveal gate flips the preshared-key visibility`() {
        val viewModel = fixture.viewModel()

        viewModel.onPresharedKeyRevealAuthorized()

        assertTrue(viewModel.uiState.value.presharedKeyRevealed)
    }

    @Test
    fun `invalid header nonce prefix blocks saving and activation until corrected`() =
        runTest {
            val vm = fixture.viewModel()
            vm.onConfPasted(
                "[Interface]\nPrivateKey = $AwgPrivateTestKey\nAddress = 10.8.0.2/32\n" +
                    "HeaderProtectionKey = AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\n" +
                    "S1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\n" +
                    "[Peer]\nPublicKey = $AwgPeerTestKey\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0",
            )
            assertTrue(vm.uiState.value.canActivate)
            vm.onFieldChanged(AwgEditorField.S3, "11")
            assertFalse(vm.uiState.value.canActivate)
            vm.onConnect()
            advanceUntilIdle()
            assertTrue(fixture.dao.allProfiles().isEmpty())
            assertNull(fixture.activator.lastActivated)
            vm.onFieldChanged(AwgEditorField.S3, "12")
            assertTrue(vm.uiState.value.canActivate)
        }

    @Test
    fun `selecting a legacy preset preserves v3 settings until replacement is confirmed`() {
        val vm = fixture.viewModel()
        vm.onConfPasted(
            "[Interface]\nPrivateKey = k==\nAddress = 10.8.0.2/32\nRandomTrailers = on\n" +
                "S1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\n" +
                "HeaderProtectionKey = AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\n" +
                "[Peer]\nPublicKey = p==\nEndpoint = vpn.example.com:51820",
        )
        val before = vm.uiState.value.editor
        vm.onCohortSelected("rtk_south")
        assertEquals(before, vm.uiState.value.editor)
        assertEquals("rtk_south", vm.uiState.value.pendingCohortId)
        vm.onCohortReplacementCancelled()
        assertNull(vm.uiState.value.pendingCohortId)
        assertEquals(before, vm.uiState.value.editor)
        vm.onCohortSelected("rtk_south")
        vm.onCohortReplacementConfirmed()
        val replaced = vm.uiState.value.editor
        assertNull(vm.uiState.value.pendingCohortId)
        assertFalse(replaced.form.v3.isSet)
        assertEquals("", replaced.rawText(AwgEditorField.HEADER_PROTECTION_KEY))
        assertEquals(before.form.interfacePrivateKey, replaced.form.interfacePrivateKey)
        assertTrue(replaced.obfuscationLocked)
        assertEquals(replaced, replaced.updateField(AwgEditorField.REKEY_TIMEOUT, "2"))
    }

    @Test
    fun `v3 edits persist through save reload and activation with the key relocked on import`() =
        runTest {
            val vm = fixture.viewModel()
            fixture.fillRequiredIdentity(vm)
            listOf(AwgEditorField.S1, AwgEditorField.S2, AwgEditorField.S3, AwgEditorField.S4).forEach {
                vm.onFieldChanged(it, "12")
            }
            val key = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="
            vm.onFieldChanged(AwgEditorField.HEADER_PROTECTION_KEY, key)
            listOf(
                AwgEditorField.CONTENT_PADDING_ADDITION,
                AwgEditorField.REKEY_AFTER_TIME,
                AwgEditorField.REKEY_TIMEOUT,
                AwgEditorField.REJECT_AFTER_TIME,
                AwgEditorField.KEEPALIVE_TIMEOUT,
                AwgEditorField.MAX_HANDSHAKE_ATTEMPTS,
            ).forEach {
                vm.onFieldChanged(it, "10-20")
            }
            vm.onFieldChanged(AwgEditorField.RANDOM_TRAILERS, "true")
            vm.onFieldChanged(AwgEditorField.DISABLE_COOKIES, "true")
            val expected = vm.uiState.value.editor.form.v3
            vm.onConnect()
            advanceUntilIdle()
            val activated = requireNotNull(fixture.activator.lastActivated)
            assertEquals(expected, activated.obfuscation.v3)
            assertEquals(
                expected,
                fixture.repository
                    .load(activated.profileId)!!
                    .request.obfuscation.v3,
            )
            assertTrue(fixture.dao.allProfiles().none { it.requestJson.contains(key) })
            vm.onHeaderProtectionKeyRevealAuthorized()
            assertTrue(vm.uiState.value.headerProtectionKeyRevealed)
            vm.onSecretsRelocked()
            assertFalse(vm.uiState.value.headerProtectionKeyRevealed)
            vm.onHeaderProtectionKeyRevealAuthorized()
            vm.onConfPasted(
                "[Interface]\nPrivateKey = k==\nAddress = 10.8.0.2/32\n" +
                    "[Peer]\nPublicKey = p==\nEndpoint = vpn.example.com:51820",
            )
            assertFalse(vm.uiState.value.headerProtectionKeyRevealed)
            assertEquals(
                "",
                vm.uiState.value.editor
                    .rawText(AwgEditorField.HEADER_PROTECTION_KEY),
            )
        }

    @Test
    fun `deleting a saved profile removes it and closes the editor`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            assertFalse(viewModel.uiState.value.savedProfile)
            viewModel.onConnect()
            advanceUntilIdle()
            val id = checkNotNull(fixture.activator.lastActivated).profileId
            assertTrue(viewModel.uiState.value.savedProfile)

            viewModel.profileDeleted.test {
                viewModel.onDelete()
                awaitItem()
            }

            assertNull(fixture.repository.load(id))
            assertFalse(viewModel.uiState.value.deleteFailed)
        }

    @Test
    fun `a failed delete keeps the profile and the editor and reports the failure`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()
            val id = checkNotNull(fixture.activator.lastActivated).profileId
            fixture.dao.failReads = true

            viewModel.profileDeleted.test {
                viewModel.onDelete()
                viewModel.uiState.first { it.deleteFailed }
                expectNoEvents()
            }

            fixture.dao.failReads = false
            assertNotNull(fixture.repository.load(id))
        }
}
