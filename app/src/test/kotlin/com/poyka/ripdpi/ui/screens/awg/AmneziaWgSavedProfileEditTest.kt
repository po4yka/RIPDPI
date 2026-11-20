package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.ui.screens.profiles.AwgProfileItem
import com.poyka.ripdpi.ui.screens.profiles.toAwgProfileItem
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

/** A subscription AWG row delivered without its key is opened by id, completed, and saved in place. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmneziaWgSavedProfileEditTest {
    private val fixture = AwgProfileViewModelFixture()

    @get:Rule
    val dispatcherRule: TestRule = fixture

    private val bundled =
        AwgActivationRequest(
            profileId = "",
            privateKey = "",
            peerPublicKey = AwgPeerTestKey,
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            dnsServers = listOf("1.1.1.1"),
        )
    private val member = AwgSubscriptionProfile("group-one", "tag:home", "Home AWG", bundled)

    @Test
    fun `typing the key into a loaded subscription profile saves the same row and connects it`() =
        runTest {
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id
            assertEquals(
                listOf(AwgProfileItem(id, "Home AWG", needsPrivateKey = true)),
                fixture.repository
                    .observeProfiles()
                    .first()
                    .map { it.toAwgProfileItem() },
            )
            val viewModel = fixture.viewModel()

            viewModel.onLoadProfile(id)

            val loaded = viewModel.uiState.value
            assertEquals("vpn.example.com", loaded.editor.form.server)
            assertEquals("", loaded.editor.form.interfacePrivateKey)
            assertFalse(loaded.canActivate)
            // The editor round-trips the stored request, so saving changes only what the user typed.
            assertEquals(fixture.repository.load(id)!!.request, loaded.editor.toActivationRequest(id))

            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)
            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(id, fixture.activator.lastActivated?.profileId)
            val saved = fixture.repository.load(id)!!
            assertEquals(AwgPrivateTestKey, saved.request.privateKey)
            assertEquals("Home AWG", saved.name)
            assertTrue(saved.subscriptionOwned)
            assertEquals(
                listOf(AwgProfileItem(id, "Home AWG", needsPrivateKey = false)),
                fixture.repository
                    .observeProfiles()
                    .first()
                    .map { it.toAwgProfileItem() },
            )
            // A later bundle import with the placeholder key keeps the typed key on the same row.
            assertEquals(
                id,
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id,
            )
            assertEquals(
                AwgPrivateTestKey,
                fixture.repository
                    .load(id)!!
                    .request.privateKey,
            )
        }

    @Test
    fun `a conf for another server cannot retarget a subscription profile`() =
        runTest {
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id
            val viewModel = fixture.viewModel()
            viewModel.onLoadProfile(id)

            viewModel.onConfPasted(conf(endpoint = "other.example.com:51820"))

            val rejected = viewModel.uiState.value
            assertEquals(AwgConfImportFailure.SUBSCRIPTION_SERVER_MISMATCH, rejected.confImportFailure?.reason)
            assertEquals("vpn.example.com", rejected.editor.form.server)
            assertEquals("", rejected.editor.form.interfacePrivateKey)

            viewModel.onConfPasted(conf(endpoint = "vpn.example.com:51820"))

            val accepted = viewModel.uiState.value
            assertNull(accepted.confImportFailure)
            assertEquals(AwgPrivateTestKey, accepted.editor.form.interfacePrivateKey)
            assertTrue(accepted.canActivate)
        }

    private fun conf(endpoint: String) =
        """
        [Interface]
        PrivateKey = $AwgPrivateTestKey
        Address = 10.8.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = $AwgPeerTestKey
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0
        """.trimIndent()
}
