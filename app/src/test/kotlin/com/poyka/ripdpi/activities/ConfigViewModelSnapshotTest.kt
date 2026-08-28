package com.poyka.ripdpi.activities

import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigViewModelSnapshotTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `telemetry and draft emissions do not repeat storage or platform snapshots`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val networkSnapshotProvider = CountingNativeNetworkSnapshotProvider()
            val relayProfileStore = CountingRelayProfileStore()
            val viewModel =
                createViewModel(
                    serviceStateStore = serviceStateStore,
                    networkSnapshotProvider = networkSnapshotProvider,
                    relayProfileStore = relayProfileStore,
                )
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(1, networkSnapshotProvider.captureCalls)
            assertEquals(1, relayProfileStore.listCalls)

            serviceStateStore.updateTelemetry(ServiceTelemetrySnapshot(updatedAt = 1L))
            runCurrent()
            serviceStateStore.updateTelemetry(ServiceTelemetrySnapshot(updatedAt = 2L))
            runCurrent()
            viewModel.updateDraft { copy(proxyPort = "1081") }
            runCurrent()

            assertEquals(1, networkSnapshotProvider.captureCalls)
            assertEquals(1, relayProfileStore.listCalls)

            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    networkHandoverState = "observed",
                    updatedAt = 3L,
                ),
            )
            runCurrent()

            assertEquals(2, networkSnapshotProvider.captureCalls)
            assertEquals(1, relayProfileStore.listCalls)
            collector.cancel()
        }

    private fun createViewModel(
        serviceStateStore: FakeServiceStateStore,
        networkSnapshotProvider: NativeNetworkSnapshotProvider,
        relayProfileStore: RelayProfileStore,
    ): ConfigViewModel {
        val appSettingsRepository = FakeAppSettingsRepository()
        return ConfigViewModel(
            savedStateHandle = SavedStateHandle(),
            dependencies =
                ConfigViewModelDependencies(
                    appSettingsRepository = appSettingsRepository,
                    relayArtifacts =
                        ConfigRelayArtifactRepository(
                            appSettingsRepository = appSettingsRepository,
                            relayProfileStore = relayProfileStore,
                            relayCredentialStore = InMemoryRelayCredentialStore(),
                        ),
                    relayPresetCatalog = RelayPresetCatalog(RuntimeEnvironment.getApplication()),
                    networkSnapshotProvider = networkSnapshotProvider,
                    serviceStateStore = serviceStateStore,
                    serviceController = FakeServiceController(),
                    latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
                    capabilityObserver =
                        ConfigCapabilityObserver(
                            networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                            serverCapabilityStore = NoOpServerCapabilityStore(),
                            dispatchers = testDispatchers(),
                        ),
                    dispatchers = testDispatchers(),
                    editorDraftStore = InMemoryConfigEditorDraftStore(),
                    xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(appSettingsRepository),
                ),
            importDependencies =
                ConfigImportDependencies(
                    masqueClientCredentialImporter = NoOpMasqueClientCredentialImporter,
                    masquePrivacyPassAvailability = NoOpMasquePrivacyPassAvailability,
                ),
            stringResolver = ResourceStringResolver(),
        )
    }
}

private class CountingNativeNetworkSnapshotProvider : NativeNetworkSnapshotProvider {
    var captureCalls = 0
        private set

    override fun capture(): NativeNetworkSnapshot {
        captureCalls += 1
        return NativeNetworkSnapshot()
    }
}

private class CountingRelayProfileStore : RelayProfileStore {
    var listCalls = 0
        private set

    override suspend fun load(profileId: String): RelayProfileRecord? = null

    override suspend fun list(): List<RelayProfileRecord> {
        listCalls += 1
        return emptyList()
    }

    override suspend fun save(profile: RelayProfileRecord) = Unit

    override suspend fun clear(profileId: String) = Unit
}
