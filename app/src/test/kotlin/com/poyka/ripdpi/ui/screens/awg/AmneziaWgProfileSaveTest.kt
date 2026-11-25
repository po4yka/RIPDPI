package com.poyka.ripdpi.ui.screens.awg

import android.content.Intent
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Tests for the profile name, Save without Connect, and key generation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmneziaWgProfileSaveTest {
    private val fixture = AwgProfileViewModelFixture()

    @get:Rule
    val dispatcherRule: TestRule = fixture

    @Test
    fun `save stores the named profile without VPN consent or activation`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onNameChanged("Home")

            viewModel.vpnConsentRequests.test {
                viewModel.onSave()
                advanceUntilIdle()
                expectNoEvents()
            }

            assertEquals(0, fixture.activator.activationCount)
            val profiles = fixture.repository.observeProfiles().first()
            assertEquals(listOf("Home"), profiles.map { it.name })
            assertTrue(viewModel.uiState.value.saved)
            assertTrue(viewModel.uiState.value.deletable)
        }

    @Test
    fun `a second save renames the same row and a reopened editor shows the name`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onNameChanged("Home")
            viewModel.onSave()
            advanceUntilIdle()

            viewModel.onNameChanged("  Office ")
            assertFalse(viewModel.uiState.value.saved)
            viewModel.onSave()
            advanceUntilIdle()

            val profiles = fixture.repository.observeProfiles().first()
            assertEquals(listOf("Office"), profiles.map { it.name })
            val reopened = fixture.viewModel()
            reopened.onLoadProfile(profiles.single().id)
            assertEquals("Office", reopened.uiState.value.name)
        }

    @Test
    fun `an edit made while saving is not reported as saved`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onSave()
            viewModel.onNameChanged("Typed during the write")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.saved)
            assertFalse(viewModel.uiState.value.saving)
        }

    @Test
    fun `a blank name saves the default name`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onSave()
            advanceUntilIdle()

            assertEquals(
                listOf("AmneziaWG"),
                fixture.repository
                    .observeProfiles()
                    .first()
                    .map { it.name },
            )
        }

    @Test
    fun `generate fills an empty private key and shows its public key until the key changes`() =
        runTest {
            val viewModel = fixture.viewModel()

            viewModel.onGenerateKeyPair()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(AwgGeneratedPrivateTestKey, state.editor.form.interfacePrivateKey)
            assertEquals(AwgGeneratedPublicTestKey, state.generatedPublicKey)

            viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")
            assertEquals(AwgGeneratedPublicTestKey, viewModel.uiState.value.generatedPublicKey)
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)
            assertNull(viewModel.uiState.value.generatedPublicKey)
        }

    @Test
    fun `generate never replaces an existing private key`() =
        runTest {
            val viewModel = fixture.viewModel()
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)

            viewModel.onGenerateKeyPair()
            advanceUntilIdle()

            assertEquals(0, fixture.keyPairGenerator.calls)
            assertEquals(AwgPrivateTestKey, viewModel.uiState.value.editor.form.interfacePrivateKey)
            assertNull(viewModel.uiState.value.generatedPublicKey)
        }

    @Test
    fun `a failed generation reports it and leaves the key empty`() =
        runTest {
            fixture.keyPairGenerator.failure = IOException("native")
            val viewModel = fixture.viewModel()

            viewModel.onGenerateKeyPair()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.keyGenerationFailed)
            assertEquals("", state.editor.form.interfacePrivateKey)
            assertNull(state.generatedPublicKey)
        }
}
