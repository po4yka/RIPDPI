package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.activities.FakePermissionPlatformBridge
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.awg.testAwgProfileRepository
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Owns one test's dispatcher and in-memory profile dependencies. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AwgProfileViewModelFixture : TestWatcher() {
    private val mainDispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(mainDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }

    val activator = RecordingStandaloneAmneziaWgActivator()
    val dao = InMemoryAwgProfileDao()
    val credentialStore: AwgCredentialStore = InMemoryAwgCredentialStore()
    val repository =
        testAwgProfileRepository(
            dao,
            credentialStore,
            com.poyka.ripdpi.data.awg
                .TestDirectAwgProfileMutationCoordinator(dao, credentialStore),
        )
    val permissionBridge = FakePermissionPlatformBridge(vpnPermissionIntent = null)

    fun viewModel() =
        AmneziaWgProfileViewModel(
            FakeCatalogProvider(AwgTestCatalog),
            activator,
            repository,
            permissionBridge,
            FakeAwgClipboardReader(),
        )

    fun fillRequiredIdentity(viewModel: AmneziaWgProfileViewModel) {
        viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")
        viewModel.onFieldChanged(AwgEditorField.SERVER_PORT, "51820")
        viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgPrivateTestKey)
        viewModel.onFieldChanged(AwgEditorField.PEER_PUBLIC_KEY, AwgPeerTestKey)
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
internal class RecordingStandaloneAmneziaWgActivator : StandaloneAmneziaWgActivator {
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
internal class InMemoryAwgProfileDao : AwgProfileDao {
    private val rows = MutableStateFlow<List<AwgProfileEntity>>(emptyList())

    /** Makes every read fail like a broken Room query. */
    var failReads = false

    private fun readable() = check(!failReads) { "Room read failed" }

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rows.asStateFlow().onEach { readable() }

    override fun allProfiles(): List<AwgProfileEntity> = rows.value.also { readable() }

    override fun getProfile(id: String): AwgProfileEntity? = rows.value.also { readable() }.firstOrNull { it.id == id }

    override fun upsertProfile(profile: AwgProfileEntity) {
        rows.value = rows.value.filterNot { it.id == profile.id } + profile
    }

    override fun deleteProfile(profile: AwgProfileEntity) {
        rows.value = rows.value.filterNot { it.id == profile.id }
    }

    override fun deleteAll() {
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
