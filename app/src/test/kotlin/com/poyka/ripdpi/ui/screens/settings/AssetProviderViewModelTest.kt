package com.poyka.ripdpi.ui.screens.settings

import android.net.Uri
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.assets.GeoAssetIntegrityException
import com.poyka.ripdpi.assets.GeoAssetIntegrityFailure
import com.poyka.ripdpi.assets.GeoAssetRepository
import com.poyka.ripdpi.assets.GeoAssetUpdateResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssetProviderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `import forwards exact kind and uri then publishes imported outcome`() =
        runTest {
            val repository = FakeGeoAssetRepository()
            val completion = CompletableDeferred<Unit>()
            repository.importAction = { completion.await() }
            val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
            val uri = Uri.parse("content://documents/geosite.db")

            viewModel.importLocalAsset(GeoAssetKind.Geosite, uri)
            runCurrent()

            assertEquals(GeoAssetKind.Geosite to uri, repository.lastImport)
            assertEquals(AssetProviderOperation.ImportGeosite, viewModel.uiState.value.activeOperation)
            assertNull(viewModel.uiState.value.lastResult)

            completion.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.activeOperation)
            assertEquals(AssetProviderCheckOutcome.Imported, viewModel.uiState.value.lastResult)
        }

    @Test
    fun `repository failures become stable localized reasons and clear operation`() =
        runTest {
            val cases =
                listOf(
                    GeoAssetIntegrityException(GeoAssetIntegrityFailure.UnableToOpen) to
                        AssetProviderFailureReason.UnableToOpen,
                    GeoAssetIntegrityException(GeoAssetIntegrityFailure.InvalidPayload) to
                        AssetProviderFailureReason.InvalidPayload,
                    GeoAssetIntegrityException(GeoAssetIntegrityFailure.TooLarge) to
                        AssetProviderFailureReason.TooLarge,
                    IOException("network unavailable") to AssetProviderFailureReason.Network,
                    IllegalStateException("unexpected") to AssetProviderFailureReason.Unexpected,
                )

            cases.forEachIndexed { index, (failure, expectedReason) ->
                val repository = FakeGeoAssetRepository()
                repository.importAction = { throw failure }
                val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
                backgroundScope.launch { viewModel.uiState.collect() }
                runCurrent()

                viewModel.importLocalAsset(GeoAssetKind.Geoip, Uri.parse("content://documents/failure-$index.db"))
                advanceUntilIdle()

                assertEquals(
                    AssetProviderCheckOutcome.Failed(expectedReason),
                    viewModel.uiState.value.lastResult,
                )
                assertNull(viewModel.uiState.value.activeOperation)
            }
        }

    @Test
    fun `one active operation blocks duplicate and cross-kind requests`() =
        runTest {
            val repository = FakeGeoAssetRepository()
            val completion = CompletableDeferred<Unit>()
            repository.checkAction = { completion.await() }
            val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.checkForUpdates()
            viewModel.checkForUpdates()
            viewModel.importLocalAsset(GeoAssetKind.Geoip, Uri.parse("content://documents/geoip.db"))
            runCurrent()

            assertEquals(1, repository.checkCalls)
            assertNull(repository.lastImport)
            assertEquals(AssetProviderOperation.CheckUpdates, viewModel.uiState.value.activeOperation)

            completion.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.activeOperation)
            assertEquals(AssetProviderCheckOutcome.UpToDate, viewModel.uiState.value.lastResult)
        }

    @Test
    fun `check operation rejects provider configuration changes until result commits`() =
        runTest {
            val settingsRepository = configuredSettingsRepository()
            val repository = FakeGeoAssetRepository()
            val completion = CompletableDeferred<Unit>()
            repository.checkResult =
                GeoAssetUpdateResult(
                    providerId = InitialProviderId,
                    geoipUpdated = true,
                    geositeUpdated = true,
                    geoipTag = ProviderAGeoipTag,
                    geositeTag = ProviderAGeositeTag,
                    anyChecked = true,
                )
            repository.checkAction = {
                completion.await()
                settingsRepository.update {
                    geoAssetGeoipVersionTag = ProviderAGeoipTag
                    geoAssetGeositeVersionTag = ProviderAGeositeTag
                }
            }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.checkForUpdates()
            runCurrent()
            viewModel.selectProvider(ChangedProviderId)
            viewModel.updateCustomBaseUrl(ChangedCustomUrl)
            runCurrent()

            assertEquals(AssetProviderOperation.CheckUpdates, viewModel.uiState.value.activeOperation)
            assertConfigurationUnchanged(settingsRepository, viewModel)

            completion.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.activeOperation)
            assertEquals(
                AssetProviderCheckOutcome.Updated(ProviderAGeoipTag, ProviderAGeositeTag),
                viewModel.uiState.value.lastResult,
            )
            assertConfigurationUnchanged(settingsRepository, viewModel)
            assertEquals(ProviderAGeoipTag, settingsRepository.snapshot().geoAssetGeoipVersionTag)
            assertEquals(ProviderAGeositeTag, settingsRepository.snapshot().geoAssetGeositeVersionTag)
            assertEquals(ProviderAGeoipTag, viewModel.uiState.value.geoipTag)
            assertEquals(ProviderAGeositeTag, viewModel.uiState.value.geositeTag)
        }

    @Test
    fun `check waits for in-flight provider mutation and uses committed provider`() =
        runTest {
            val initialSettings = configuredSettingsRepository().snapshot()
            val settingsRepository = BlockingAppSettingsRepository(initialSettings)
            val repository = FakeGeoAssetRepository()
            repository.checkResult =
                GeoAssetUpdateResult(
                    providerId = ChangedProviderId,
                    geoipUpdated = true,
                    geositeUpdated = true,
                    geoipTag = ProviderBGeoipTag,
                    geositeTag = ProviderBGeositeTag,
                    anyChecked = true,
                )
            repository.checkAction = {
                repository.observedProviderId = settingsRepository.snapshot().geoAssetProviderId
                settingsRepository.update {
                    geoAssetGeoipVersionTag = ProviderBGeoipTag
                    geoAssetGeositeVersionTag = ProviderBGeositeTag
                }
            }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.selectProvider(ChangedProviderId)
            settingsRepository.updateStarted.await()
            viewModel.checkForUpdates()
            runCurrent()

            assertEquals(AssetProviderOperation.CheckUpdates, viewModel.uiState.value.activeOperation)
            assertEquals(0, repository.checkCalls)
            assertNull(repository.observedProviderId)

            settingsRepository.continueUpdate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.checkCalls)
            assertEquals(ChangedProviderId, repository.observedProviderId)
            assertEquals(ChangedProviderId, settingsRepository.snapshot().geoAssetProviderId)
            assertEquals(ChangedProviderId, viewModel.uiState.value.providerId)
            assertEquals(ProviderBGeoipTag, settingsRepository.snapshot().geoAssetGeoipVersionTag)
            assertEquals(ProviderBGeositeTag, settingsRepository.snapshot().geoAssetGeositeVersionTag)
            assertEquals(
                AssetProviderCheckOutcome.Updated(ProviderBGeoipTag, ProviderBGeositeTag),
                viewModel.uiState.value.lastResult,
            )
        }

    @Test
    fun `import operation rejects provider configuration changes until result commits`() =
        runTest {
            val settingsRepository = configuredSettingsRepository()
            val repository = FakeGeoAssetRepository()
            val completion = CompletableDeferred<Unit>()
            repository.importAction = { completion.await() }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.importLocalAsset(GeoAssetKind.Geosite, Uri.parse("content://documents/geosite.db"))
            runCurrent()
            viewModel.selectProvider(ChangedProviderId)
            viewModel.updateCustomBaseUrl(ChangedCustomUrl)
            runCurrent()

            assertEquals(AssetProviderOperation.ImportGeosite, viewModel.uiState.value.activeOperation)
            assertConfigurationUnchanged(settingsRepository, viewModel)

            completion.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.activeOperation)
            assertEquals(AssetProviderCheckOutcome.Imported, viewModel.uiState.value.lastResult)
            assertConfigurationUnchanged(settingsRepository, viewModel)
        }

    @Test
    fun `cancellation clears operation without publishing failure`() =
        runTest {
            val repository = FakeGeoAssetRepository()
            repository.importAction = { throw CancellationException("synthetic cancellation") }
            val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.importLocalAsset(GeoAssetKind.Geoip, Uri.parse("content://documents/geoip.db"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.activeOperation)
            assertNull(viewModel.uiState.value.lastResult)
        }

    private suspend fun configuredSettingsRepository(): FakeAppSettingsRepository =
        FakeAppSettingsRepository().also { repository ->
            repository.update {
                geoAssetProviderId = InitialProviderId
                geoAssetCustomBaseUrl = InitialCustomUrl
            }
        }

    private suspend fun assertConfigurationUnchanged(
        settingsRepository: FakeAppSettingsRepository,
        viewModel: AssetProviderViewModel,
    ) {
        val settings = settingsRepository.snapshot()
        assertEquals(InitialProviderId, settings.geoAssetProviderId)
        assertEquals(InitialCustomUrl, settings.geoAssetCustomBaseUrl)
        assertEquals(InitialProviderId, viewModel.uiState.value.providerId)
        assertEquals(InitialCustomUrl, viewModel.uiState.value.customBaseUrl)
    }

    private class FakeGeoAssetRepository : GeoAssetRepository {
        var lastImport: Pair<GeoAssetKind, Uri>? = null
        var importAction: suspend () -> Unit = {}
        var checkAction: suspend () -> Unit = {}
        var checkCalls: Int = 0
        var observedProviderId: String? = null
        var checkResult =
            GeoAssetUpdateResult(
                providerId = InitialProviderId,
                geoipUpdated = false,
                geositeUpdated = false,
                geoipTag = "v1",
                geositeTag = "v1",
                anyChecked = true,
            )

        override suspend fun checkAndUpdate(): GeoAssetUpdateResult {
            checkCalls += 1
            checkAction()
            return checkResult
        }

        override suspend fun importLocalAsset(
            kind: GeoAssetKind,
            uri: Uri,
        ) {
            lastImport = kind to uri
            importAction()
        }
    }

    private class BlockingAppSettingsRepository(
        initialSettings: AppSettings,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(initialSettings)
        val updateStarted = CompletableDeferred<Unit>()
        val continueUpdate = CompletableDeferred<Unit>()

        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            updateStarted.complete(Unit)
            continueUpdate.await()
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
        }
    }

    private companion object {
        const val InitialProviderId = "sagernet"
        const val InitialCustomUrl = "https://provider-a.example/assets"
        const val ChangedProviderId = "soffchen"
        const val ChangedCustomUrl = "https://provider-b.example/assets"
        const val ProviderAGeoipTag = "provider-a-geoip-v2"
        const val ProviderAGeositeTag = "provider-a-geosite-v2"
        const val ProviderBGeoipTag = "provider-b-geoip-v2"
        const val ProviderBGeositeTag = "provider-b-geosite-v2"
    }
}
