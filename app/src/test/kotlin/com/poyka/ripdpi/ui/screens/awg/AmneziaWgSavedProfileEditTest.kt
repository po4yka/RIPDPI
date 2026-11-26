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
    fun `a subscription profile keeps refresh-owned fields read-only and the user's fields editable`() =
        runTest {
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id
            val viewModel = fixture.viewModel()
            viewModel.onLoadProfile(id)
            val loadedMtu =
                viewModel.uiState.value.editor
                    .rawText(AwgEditorField.MTU)

            viewModel.onFieldChanged(AwgEditorField.SERVER, "other.example.com")
            viewModel.onFieldChanged(AwgEditorField.MTU, "1200")
            viewModel.onFieldChanged(AwgEditorField.DNS, "9.9.9.9")
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)

            val editor = viewModel.uiState.value.editor
            assertTrue(editor.isManaged(AwgEditorField.SERVER))
            assertFalse(editor.isManaged(AwgEditorField.INTERFACE_PRIVATE_KEY))
            assertEquals("vpn.example.com", editor.form.server)
            assertEquals(loadedMtu, editor.rawText(AwgEditorField.MTU))
            assertEquals("9.9.9.9", editor.rawText(AwgEditorField.DNS))
            assertEquals(AwgPrivateTestKey, editor.form.interfacePrivateKey)
            assertFalse(viewModel.uiState.value.subscriptionServerChanged)
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

    @Test
    fun `a conf for the same server matches after host and key normalisation`() =
        runTest {
            val ipv6 = member.copy(request = bundled.copy(endpointHost = "2001:db8::1"))
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(ipv6))
                    .single()
                    .id
            val viewModel = fixture.viewModel()
            viewModel.onLoadProfile(id)

            viewModel.onConfPasted(conf(endpoint = "[2001:DB8::1]:51820"))

            assertNull(viewModel.uiState.value.confImportFailure)
            assertEquals(AwgPrivateTestKey, viewModel.uiState.value.editor.form.interfacePrivateKey)
            assertEquals(
                awgServerIdentity("vpn.example.com", 51820, AwgPeerTestKey),
                awgServerIdentity(" VPN.Example.COM ", 51820, " $AwgPeerTestKey "),
            )
        }

    @Test
    fun `a typed server, port, or peer key never changes the subscription row`() =
        runTest {
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id
            listOf(
                AwgEditorField.SERVER to "other.example.com",
                AwgEditorField.SERVER_PORT to "443",
                AwgEditorField.PEER_PUBLIC_KEY to "not base64!",
                AwgEditorField.SERVER_PORT to "port",
            ).forEach { (field, value) ->
                val viewModel = fixture.viewModel()
                viewModel.onLoadProfile(id)
                val loaded = viewModel.uiState.value.editor
                viewModel.onFieldChanged(field, value)

                // The field is read-only: the edit never reaches the form, so Connect cannot save it.
                assertEquals(field.name, loaded, viewModel.uiState.value.editor)
            }
            assertEquals(bundled.copy(profileId = id), fixture.repository.load(id)!!.request)
        }

    @Test
    fun `an unpadded peer key names the same server`() {
        assertEquals(
            awgServerIdentity("vpn.example.com", 51820, AwgPeerTestKey),
            awgServerIdentity("vpn.example.com", 51820, AwgPeerTestKey.trimEnd('=')),
        )
    }

    @Test
    fun `a profile that cannot be loaded shows the error and never saves a new row`() =
        runTest {
            fixture.dao.failReads = true
            val viewModel = fixture.viewModel()

            viewModel.onLoadProfile("awg-missing")

            assertEquals(AwgActivationStatus.Failed, viewModel.uiState.value.activationStatus)
            fixture.dao.failReads = false
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(AwgActivationStatus.Failed, viewModel.uiState.value.activationStatus)
            assertNull(fixture.activator.lastActivated)
            assertTrue(
                fixture.repository
                    .observeProfiles()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `a failed load of the same id loads on retry`() =
        runTest {
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id
            val viewModel = fixture.viewModel()
            fixture.dao.failReads = true
            viewModel.onLoadProfile(id)
            fixture.dao.failReads = false

            viewModel.onLoadProfile(id)

            assertEquals("vpn.example.com", viewModel.uiState.value.editor.form.server)
            assertFalse(viewModel.uiState.value.loadingProfile)
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)
            viewModel.onConnect()
            advanceUntilIdle()
            assertEquals(id, fixture.activator.lastActivated?.profileId)
        }

    @Test
    fun `only a keyed local UDP server offers share, as subscription servers stay with their subscription`() =
        runTest {
            val keyed = bundled.copy(privateKey = AwgPrivateTestKey)
            val subscriptionId =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member.copy(request = keyed)))
                    .single()
                    .id
            val localId = fixture.repository.save("Own", keyed)
            val wsId =
                fixture.repository.save(
                    "Own WS",
                    keyed.copy(carrier = AwgActivationRequest.CARRIER_WS, carrierWsUrl = "wss://example.com/wg"),
                )

            val shareable =
                fixture.repository
                    .observeProfiles()
                    .first()
                    .associate { it.id to it.toAwgProfileItem().shareable }

            assertEquals(mapOf(subscriptionId to false, localId to true, wsId to false), shareable)
        }

    @Test
    fun `saving a bundle-shaped row with only the key typed keeps every other field`() =
        runTest {
            val template = bundleShaped.copy(profileId = "", privateKey = "")
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(
                        listOf(AwgSubscriptionProfile("group-one", "tag:home", "Home AWG", template, true, true)),
                    ).single()
                    .id
            val viewModel = fixture.viewModel()
            viewModel.onLoadProfile(id)
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)

            viewModel.onConnect()
            advanceUntilIdle()

            val expected = template.copy(profileId = id, privateKey = AwgPrivateTestKey)
            assertEquals(expected, fixture.activator.lastActivated)
            assertEquals(expected, fixture.repository.load(id)!!.request)
        }

    @Test
    fun `a matching conf with an empty private key keeps the typed key`() =
        runTest {
            val id =
                fixture.repository
                    .saveSubscriptionProfiles(listOf(member))
                    .single()
                    .id
            val viewModel = fixture.viewModel()
            viewModel.onLoadProfile(id)
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)

            viewModel.onConfPasted(conf(endpoint = "vpn.example.com:51820", privateKey = ""))

            assertNull(viewModel.uiState.value.confImportFailure)
            assertEquals(AwgPrivateTestKey, viewModel.uiState.value.editor.form.interfacePrivateKey)
            assertTrue(viewModel.uiState.value.canActivate)
        }

    @Test
    fun `a bundle-shaped request survives opening it in the editor unchanged`() {
        bundleShaped.obfuscation.requireSupportedProfile()

        assertEquals(bundleShaped, bundleShaped.toEditorState().toActivationRequest(profileId = "awg-bundle"))
    }

    private val bundleShaped =
        AwgActivationRequest(
            profileId = "awg-bundle",
            privateKey = AwgPrivateTestKey,
            peerPublicKey = AwgPeerTestKey,
            presharedKey =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(32) { 3 }),
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            interfaceAddressV6 = "fd00::2/128",
            dnsServers = listOf("1.1.1.1", "2606:4700:4700::1111"),
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            mtu = 1420,
            persistentKeepalive = 25,
            obfuscation =
                com.poyka.ripdpi.data.awg.AwgActivationObfuscation(
                    wireProfile = "awg-3.1-ranges-v1",
                    jc = 4,
                    jmin = 40,
                    jmax = 70,
                    s1 = 50,
                    s2 = 100,
                    h1 = "100-199",
                    h2 = "200-299",
                    h3 = "300-399",
                    h4 = "400-499",
                ),
        )

    private fun conf(
        endpoint: String,
        privateKey: String = AwgPrivateTestKey,
    ) = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.8.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = $AwgPeerTestKey
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0
        """.trimIndent()
}
