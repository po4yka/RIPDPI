package com.poyka.ripdpi.ui.screens.awg

import android.content.Intent
import app.cash.turbine.test
import com.poyka.ripdpi.activities.FakePermissionPlatformBridge
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AmneziaWgProfileViewModel]: the AmneziaWG profile-editor backing ViewModel.
 *
 * The editor surfaces every obfuscation field inline. A cohort preset fills and locks the
 * obfuscation group; "Custom" frees it. These tests inject a fake catalog so they stay
 * pure-JVM (no Android asset loading) and a fake activator that records the dispatched
 * [AwgActivationRequest] without touching the native runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmneziaWgProfileViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
            h1 = 1_000_000_001L,
            h2 = 1_000_000_002L,
            h3 = 1_000_000_003L,
            h4 = 1_000_000_004L,
            randomizeHeaders = false,
        )
    private val catalog = AwgCohortCatalogData(presets = listOf(rtkSouth))
    private val activator = RecordingStandaloneAmneziaWgActivator()
    private val dao = InMemoryAwgProfileDao()
    private val credentialStore = InMemoryAwgCredentialStore()
    private val repository = AwgProfileRepository(dao, credentialStore)
    private val permissionBridge = FakePermissionPlatformBridge(vpnPermissionIntent = null)

    private fun viewModel() =
        AmneziaWgProfileViewModel(
            FakeCatalogProvider(catalog),
            activator,
            repository,
            permissionBridge,
            FakeAwgClipboardReader(),
        )

    @Test
    fun `connect waits for VPN consent before persisting or activating`() =
        runTest {
            permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            assertNull(activator.lastActivated)
            assertTrue(repository.observeProfiles().first().isEmpty())
        }

    @Test
    fun `confirmed consent activates once despite duplicate callbacks and taps`() =
        runTest {
            permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                viewModel.onConnect()
                advanceUntilIdle()
                val request = awaitItem()
                expectNoEvents()
                permissionBridge.vpnPermissionIntent = null

                viewModel.onVpnConsentResult(request.id, granted = true)
                viewModel.onVpnConsentResult(request.id, granted = true)
                advanceUntilIdle()

                assertEquals(1, activator.activationCount)
                assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
            }
        }

    @Test
    fun `denied consent does not save a profile or activate the service`() =
        runTest {
            permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()
                val request = awaitItem()

                viewModel.onVpnConsentResult(request.id, granted = false)
                advanceUntilIdle()

                assertEquals(0, activator.activationCount)
                assertTrue(repository.observeProfiles().first().isEmpty())
                assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
            }
        }

    @Test
    fun `successful activity result without platform consent cannot activate`() =
        runTest {
            permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()

                viewModel.onVpnConsentResult(awaitItem().id, granted = true)
                advanceUntilIdle()

                assertEquals(0, activator.activationCount)
                assertTrue(repository.observeProfiles().first().isEmpty())
            }
        }

    @Test
    fun `stale permission result cannot consume a newer connect attempt`() =
        runTest {
            permissionBridge.vpnPermissionIntent = Intent("test.vpn.consent")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.vpnConsentRequests.test {
                viewModel.onConnect()
                advanceUntilIdle()
                val previousRequest = awaitItem()
                viewModel.onVpnConsentResult(previousRequest.id, granted = false)
                viewModel.onConnect()
                advanceUntilIdle()
                val currentRequest = awaitItem()
                permissionBridge.vpnPermissionIntent = null

                viewModel.onVpnConsentResult(previousRequest.id, granted = true)
                advanceUntilIdle()
                assertEquals(0, activator.activationCount)
                viewModel.onVpnConsentResult(currentRequest.id, granted = true)
                advanceUntilIdle()

                assertEquals(1, activator.activationCount)
            }
        }

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

    @Test
    fun `a fresh editor cannot activate and connect is a no-op`() =
        runTest {
            val viewModel = viewModel()

            assertFalse(viewModel.uiState.value.canActivate)
            viewModel.onConnect()
            advanceUntilIdle()

            assertNull(activator.lastActivated)
        }

    @Test
    fun `filling the required identity fields makes the editor activatable`() {
        val viewModel = viewModel()

        fillRequiredIdentity(viewModel)

        assertTrue(viewModel.uiState.value.canActivate)
    }

    @Test
    fun `repeated connect taps dispatch only one in-flight activation`() =
        runTest {
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(1, activator.activationCount)
        }

    @Test
    fun `connect dispatches an activation request carrying PSK and keepalive to the service`() =
        runTest {
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.onFieldChanged(AwgEditorField.PRESHARED_KEY, "psk-material==")
            viewModel.onFieldChanged(AwgEditorField.PERSISTENT_KEEPALIVE, "37")
            viewModel.onFieldChanged(AwgEditorField.MTU, "1280")

            viewModel.onConnect()
            advanceUntilIdle()

            val request =
                requireNotNull(activator.lastActivated) { "expected the service to be activated" }
            assertEquals("vpn.example.com", request.endpointHost)
            assertEquals(51820, request.endpointPort)
            assertEquals("privkey==", request.privateKey)
            assertEquals("peerpub==", request.peerPublicKey)
            assertEquals("psk-material==", request.presharedKey)
            assertEquals(37, request.persistentKeepalive)
            assertEquals(1280, request.mtu)
            assertEquals("10.8.0.2/32", request.interfaceAddressV4)
        }

    @Test
    fun `connect carries the locked cohort obfuscation including special junk to the service`() =
        runTest {
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.onCohortSelected("rtk_south")

            viewModel.onConnect()
            advanceUntilIdle()

            val obf =
                requireNotNull(activator.lastActivated) { "expected the service to be activated" }
                    .obfuscation
            assertEquals(4, obf.jc)
            assertEquals(70, obf.jmax)
            assertEquals(1_000_000_001L, obf.h1)
        }

    @Test
    fun `the activation profile id is an opaque uuid, not derived from the endpoint`() =
        runTest {
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            val profileId =
                requireNotNull(activator.lastActivated) { "expected the service to be activated" }.profileId
            // Must NOT leak the peer host/port (network-fingerprint-privacy.md hard rule).
            assertFalse(profileId.contains("vpn.example.com"))
            assertFalse(profileId.contains("51820"))
            assertTrue(profileId.startsWith("awg-"))
        }

    @Test
    fun `re-connecting reuses the same persisted profile id`() =
        runTest {
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()
            val firstId =
                requireNotNull(activator.lastActivated) { "expected the first activation" }.profileId

            // Edit the profile and re-connect: the stable id must be reused, not re-minted.
            viewModel.onFieldChanged(AwgEditorField.PERSISTENT_KEEPALIVE, "42")
            viewModel.onConnect()
            advanceUntilIdle()
            val secondId =
                requireNotNull(activator.lastActivated) { "expected the second activation" }.profileId

            assertEquals("re-connect must reuse the persisted stable id", firstId, secondId)
            // And the store holds exactly one row -- re-save updated it in place.
            assertEquals(1, repository.observeProfiles().first().size)
        }

    @Test
    fun `connect persists the profile so it survives across editor instances`() =
        runTest {
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()
            val id =
                requireNotNull(activator.lastActivated) { "expected the activation" }.profileId

            // The persisted row carries the same stable id and re-hydrates the endpoint config.
            val saved = repository.load(id)!!
            assertEquals(id, saved.request.profileId)
            assertEquals("vpn.example.com", saved.request.endpointHost)
        }

    @Test
    fun `cancelled activation releases the in-flight Connect guard`() =
        runTest {
            activator.failure = CancellationException("activation superseded")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
        }

    @Test
    fun `a failed activation surfaces an error status instead of being dropped`() =
        runTest {
            activator.failure = IllegalStateException("runtime failed to reach readiness")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)

            viewModel.onConnect()
            advanceUntilIdle()

            assertEquals(AwgActivationStatus.Failed, viewModel.uiState.value.activationStatus)
        }

    @Test
    fun `editing after a failed activation clears the error status`() =
        runTest {
            activator.failure = IllegalStateException("runtime failed to reach readiness")
            val viewModel = viewModel()
            fillRequiredIdentity(viewModel)
            viewModel.onConnect()
            advanceUntilIdle()
            assertEquals(AwgActivationStatus.Failed, viewModel.uiState.value.activationStatus)

            viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn2.example.com")

            assertEquals(AwgActivationStatus.Idle, viewModel.uiState.value.activationStatus)
        }

    private fun fillRequiredIdentity(viewModel: AmneziaWgProfileViewModel) {
        viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")
        viewModel.onFieldChanged(AwgEditorField.SERVER_PORT, "51820")
        viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, "privkey==")
        viewModel.onFieldChanged(AwgEditorField.PEER_PUBLIC_KEY, "peerpub==")
        viewModel.onFieldChanged(AwgEditorField.ADDRESS, "10.8.0.2/32")
    }
}

/** Test double that returns a fixed catalog without touching Android assets. */
private class FakeCatalogProvider(
    private val catalog: AwgCohortCatalogData,
) : AwgCohortCatalogProvider {
    override fun catalog(): AwgCohortCatalogData = catalog
}

/** Records the dispatched activation request without driving the native runtime. */
private class RecordingStandaloneAmneziaWgActivator : StandaloneAmneziaWgActivator {
    var activationCount: Int = 0
        private set

    var lastActivated: AwgActivationRequest? = null
        private set

    /** When non-null, [activate] throws it (simulating a startup failure from the service). */
    var failure: Throwable? = null

    override suspend fun activate(request: AwgActivationRequest) {
        activationCount += 1
        failure?.let { throw it }
        lastActivated = request
    }

    override suspend fun deactivate() {
        lastActivated = null
    }
}

/**
 * In-memory [AwgProfileDao] so the test drives the REAL [AwgProfileRepository]
 * (and therefore the real stable-id discipline) without a Room dependency.
 */
private class InMemoryAwgProfileDao : AwgProfileDao {
    private val rows = MutableStateFlow<List<AwgProfileEntity>>(emptyList())

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rows.asStateFlow()

    override suspend fun allProfiles(): List<AwgProfileEntity> = rows.value

    override suspend fun getProfile(id: String): AwgProfileEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun upsertProfile(profile: AwgProfileEntity) {
        rows.value = rows.value.filterNot { it.id == profile.id } + profile
    }

    override suspend fun deleteProfile(profile: AwgProfileEntity) {
        rows.value = rows.value.filterNot { it.id == profile.id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/**
 * In-memory [AwgCredentialStore] so the ViewModel test drives the REAL
 * [AwgProfileRepository] secret-split without an AndroidKeyStore dependency.
 */
private class InMemoryAwgCredentialStore : AwgCredentialStore {
    private val secrets = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String): AwgSecrets? = secrets[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        this.secrets[profileId] = secrets
    }

    override suspend fun clear(profileId: String) {
        secrets.remove(profileId)
    }
}
