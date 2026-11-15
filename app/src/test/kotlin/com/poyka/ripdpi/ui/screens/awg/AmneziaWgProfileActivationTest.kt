package com.poyka.ripdpi.ui.screens.awg

import android.content.Intent
import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
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
import java.util.Base64

/** Tests for VPN consent, activation, and persisted profiles. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmneziaWgProfileActivationTest {
    private val fixture = AwgProfileViewModelFixture()

    @get:Rule
    val dispatcherRule: TestRule = fixture

    @Test
    fun `connect waits for VPN consent before persisting or activating`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            assertNull(fixture.activator.lastActivated)
            assertTrue(
                fixture.repository
                    .observeProfiles()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `confirmed consent activates once despite duplicate callbacks and taps`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                viewModel.onConnect()
                advanceUntilIdle()
                val request = awaitItem()
                expectNoEvents()
                fixture.permissionBridge.vpnPermissionIntent = null

                viewModel.onVpnConsentResult(request.id, granted = true)
                viewModel.onVpnConsentResult(request.id, granted = true)
                advanceUntilIdle()

                assertEquals(1, fixture.activator.activationCount)
                assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
            }
        }

    @Test
    fun `consent for an edited draft cannot save the obsolete request`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()
                val consent = awaitItem()

                viewModel.onFieldChanged(AwgEditorField.SERVER, "replacement.example.com")
                fixture.permissionBridge.vpnPermissionIntent = null
                viewModel.onVpnConsentResult(consent.id, granted = true)
                advanceUntilIdle()

                assertTrue(
                    fixture.repository
                        .observeProfiles()
                        .first()
                        .isEmpty(),
                )
                assertEquals(0, fixture.activator.activationCount)
                assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)

                viewModel.onConnect()
                advanceUntilIdle()
                val saved =
                    fixture.repository
                        .observeProfiles()
                        .first()
                        .single()
                        .request
                assertEquals("replacement.example.com", saved.endpointHost)
                assertEquals(1, fixture.activator.activationCount)
            }
        }

    @Test
    fun `denied consent does not save a profile or activate the service`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()
                val request = awaitItem()

                viewModel.onVpnConsentResult(request.id, granted = false)
                advanceUntilIdle()

                assertEquals(0, fixture.activator.activationCount)
                assertTrue(
                    fixture.repository
                        .observeProfiles()
                        .first()
                        .isEmpty(),
                )
                assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
            }
        }

    @Test
    fun `successful activity result without platform consent cannot activate`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()

                viewModel.onVpnConsentResult(awaitItem().id, granted = true)
                advanceUntilIdle()

                assertEquals(0, fixture.activator.activationCount)
                assertTrue(
                    fixture.repository
                        .observeProfiles()
                        .first()
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `stale permission result cannot consume a newer connect attempt`() =
        runTest {
            fixture.permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()
                val previousRequest = awaitItem()
                viewModel.onVpnConsentResult(previousRequest.id, granted = false)
                viewModel.onConnect()
                advanceUntilIdle()
                val currentRequest = awaitItem()
                fixture.permissionBridge.vpnPermissionIntent = null

                viewModel.onVpnConsentResult(previousRequest.id, granted = true)
                advanceUntilIdle()
                assertEquals(0, fixture.activator.activationCount)
                viewModel.onVpnConsentResult(currentRequest.id, granted = true)
                advanceUntilIdle()

                assertEquals(1, fixture.activator.activationCount)
            }
        }

    @Test
    fun `a fresh editor cannot activate and connect is a no-op`() =
        runTest {
            val viewModel = fixture.viewModel()

            assertFalse(viewModel.uiState.value.canActivate)
            viewModel.onConnect()
            advanceUntilIdle()

            assertNull(fixture.activator.lastActivated)
        }

    @Test
    fun `filling the required identity fields makes the editor activatable`() {
        val viewModel = fixture.viewModel()

        fixture.fillRequiredIdentity(viewModel)

        assertTrue(viewModel.uiState.value.canActivate)
    }

    @Test
    fun `repeated connect taps dispatch only one in-flight activation`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(1, fixture.activator.activationCount)
        }

    @Test
    fun `connect dispatches an activation request carrying PSK and keepalive to the service`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            val psk = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
            viewModel.onFieldChanged(AwgEditorField.PRESHARED_KEY, psk)
            viewModel.onFieldChanged(AwgEditorField.PERSISTENT_KEEPALIVE, "37")
            viewModel.onFieldChanged(AwgEditorField.MTU, "1280")

            viewModel.onConnect()
            advanceUntilIdle()

            val request =
                requireNotNull(fixture.activator.lastActivated) { "expected the service to be activated" }
            assertEquals("vpn.example.com", request.endpointHost)
            assertEquals(51820, request.endpointPort)
            assertEquals(AwgPrivateTestKey, request.privateKey)
            assertEquals(AwgPeerTestKey, request.peerPublicKey)
            assertEquals(psk, request.presharedKey)
            assertEquals(37, request.persistentKeepalive)
            assertEquals(1280, request.mtu)
            assertEquals("10.8.0.2/32", request.interfaceAddressV4)
        }

    @Test
    fun `connect carries the locked cohort obfuscation including special junk to the service`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onCohortSelected("rtk_south")

            viewModel.onConnect()
            advanceUntilIdle()

            val obf =
                requireNotNull(fixture.activator.lastActivated) { "expected the service to be activated" }
                    .obfuscation
            assertEquals(4, obf.jc)
            assertEquals(70, obf.jmax)
            assertEquals("1000000001", obf.h1)
        }

    @Test
    fun `the activation profile id is an opaque uuid, not derived from the endpoint`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            val profileId =
                requireNotNull(fixture.activator.lastActivated) { "expected the service to be activated" }.profileId
            // Must NOT leak the peer host/port (network-fingerprint-privacy.md hard rule).
            assertFalse(profileId.contains("vpn.example.com"))
            assertFalse(profileId.contains("51820"))
            assertTrue(profileId.startsWith("awg-"))
        }

    @Test
    fun `a malformed imported draft cannot replace saved settings or credentials`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()
            val saved =
                fixture.repository
                    .observeProfiles()
                    .first()
                    .single()
                    .request
            val rows = fixture.dao.allProfiles()
            val secrets = fixture.credentialStore.load(saved.profileId)

            viewModel.onConfPasted(
                """
                [Interface]
                PrivateKey = malformed-key
                Address = 10.8.0.3/32
                [Peer]
                PublicKey = $AwgPeerTestKey
                Endpoint = replacement.example.com:51820
                AllowedIPs = 0.0.0.0/0
                """.trimIndent(),
            )

            assertFalse(viewModel.uiState.value.canActivate)
            viewModel.onConnect()
            advanceUntilIdle()
            assertEquals(rows, fixture.dao.allProfiles())
            assertEquals(secrets, fixture.credentialStore.load(saved.profileId))
            assertEquals(saved, fixture.repository.load(saved.profileId)?.request)
            assertEquals(1, fixture.activator.activationCount)
        }

    @Test
    fun `re-connecting reuses the same persisted profile id`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()
            val firstId =
                requireNotNull(fixture.activator.lastActivated) { "expected the first activation" }.profileId

            // Edit the profile and re-connect: the stable id must be reused, not re-minted.
            viewModel.onFieldChanged(AwgEditorField.PERSISTENT_KEEPALIVE, "42")
            viewModel.onConnect()
            advanceUntilIdle()
            val secondId =
                requireNotNull(fixture.activator.lastActivated) { "expected the second activation" }.profileId

            assertEquals("re-connect must reuse the persisted stable id", firstId, secondId)
            // And the store holds exactly one row -- re-save updated it in place.
            assertEquals(
                1,
                fixture.repository
                    .observeProfiles()
                    .first()
                    .size,
            )
        }

    @Test
    fun `connect persists the profile so it survives across editor instances`() =
        runTest {
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()
            val id =
                requireNotNull(fixture.activator.lastActivated) { "expected the activation" }.profileId

            // The persisted row carries the same stable id and re-hydrates the endpoint config.
            val saved = fixture.repository.load(id)!!
            assertEquals(id, saved.request.profileId)
            assertEquals("vpn.example.com", saved.request.endpointHost)
        }

    @Test
    fun `cancelled activation releases the in-flight Connect guard`() =
        runTest {
            fixture.activator.failure = CancellationException("activation superseded")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
        }

    @Test
    fun `a failed activation surfaces an error status instead of being dropped`() =
        runTest {
            fixture.activator.failure = IllegalStateException("runtime failed to reach readiness")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(AwgActivationStatus.Failed, viewModel.uiState.value.activationStatus)
        }

    @Test
    fun `editing after a failed activation clears the error status`() =
        runTest {
            fixture.activator.failure = IllegalStateException("runtime failed to reach readiness")
            val viewModel = fixture.viewModel()
            fixture.fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()
            assertEquals(AwgActivationStatus.Failed, viewModel.uiState.value.activationStatus)

            viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn2.example.com")

            assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
        }
}
