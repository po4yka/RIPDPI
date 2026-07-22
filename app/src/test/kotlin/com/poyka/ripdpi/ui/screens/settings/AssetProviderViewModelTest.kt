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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `download metadata storage failure maps to storage banner reason`() =
        runTest {
            val repository = FakeGeoAssetRepository()
            val storageFailure = IOException("local storage unavailable")
            repository.checkAction = {
                throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.InstallFailed, storageFailure)
            }
            val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.checkForUpdates()
            advanceUntilIdle()

            assertEquals(
                AssetProviderCheckOutcome.Failed(AssetProviderFailureReason.Storage),
                viewModel.uiState.value.lastResult,
            )
            assertNull(viewModel.uiState.value.activeOperation)
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
    fun `custom URL draft stays responsive while persistence is blocked`() =
        runTest {
            val initialSettings = configuredSettingsRepository().snapshot()
            val settingsRepository = BlockingAppSettingsRepository(initialSettings)
            val viewModel = AssetProviderViewModel(settingsRepository, FakeGeoAssetRepository())
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.updateCustomBaseUrl(" https://assets.example/first ")
            settingsRepository.updateStarted.await()
            viewModel.updateCustomBaseUrl(" https://assets.example/latest ")
            runCurrent()

            assertEquals(" https://assets.example/latest ", viewModel.uiState.value.customBaseUrl)
            assertEquals(InitialCustomUrl, settingsRepository.snapshot().geoAssetCustomBaseUrl)

            settingsRepository.continueUpdate.complete(Unit)
            advanceUntilIdle()

            assertEquals("https://assets.example/latest", settingsRepository.snapshot().geoAssetCustomBaseUrl)
            assertEquals(" https://assets.example/latest ", viewModel.uiState.value.customBaseUrl)
        }

    @Test
    fun `check immediately after configuration edits uses latest accepted drafts`() =
        runTest {
            val settingsRepository = configuredSettingsRepository()
            val repository = FakeGeoAssetRepository()
            repository.checkAction = {
                val settings = settingsRepository.snapshot()
                repository.observedProviderId = settings.geoAssetProviderId
                repository.observedCustomBaseUrl = settings.geoAssetCustomBaseUrl
            }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.selectProvider(ChangedProviderId)
            viewModel.updateCustomBaseUrl("  $ChangedCustomUrl  ")
            viewModel.checkForUpdates()
            advanceUntilIdle()

            assertEquals(ChangedProviderId, repository.observedProviderId)
            assertEquals(ChangedCustomUrl, repository.observedCustomBaseUrl)
            assertEquals(ChangedProviderId, settingsRepository.snapshot().geoAssetProviderId)
            assertEquals(ChangedCustomUrl, settingsRepository.snapshot().geoAssetCustomBaseUrl)
        }

    @Test
    fun `persistence failure warns retries and manual retry saves latest draft`() =
        runTest {
            val initialSettings = configuredSettingsRepository().snapshot()
            val settingsRepository = FailingAppSettingsRepository(initialSettings, failuresRemaining = 3)
            val viewModel = AssetProviderViewModel(settingsRepository, FakeGeoAssetRepository())
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.updateCustomBaseUrl("  $ChangedCustomUrl  ")
            advanceUntilIdle()

            assertEquals(3, settingsRepository.updateCalls)
            assertTrue(viewModel.uiState.value.hasPersistenceError)
            assertEquals("  $ChangedCustomUrl  ", viewModel.uiState.value.customBaseUrl)
            assertEquals(InitialCustomUrl, settingsRepository.snapshot().geoAssetCustomBaseUrl)

            settingsRepository.failuresRemaining = 0
            viewModel.retryConfigurationPersistence()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasPersistenceError)
            assertEquals(ChangedCustomUrl, settingsRepository.snapshot().geoAssetCustomBaseUrl)
        }

    @Test
    fun `acknowledged exit flush survives ViewModel recreation`() =
        runTest {
            val settingsRepository = configuredSettingsRepository()
            val firstViewModel = AssetProviderViewModel(settingsRepository, FakeGeoAssetRepository())
            backgroundScope.launch { firstViewModel.uiState.collect() }
            runCurrent()

            firstViewModel.selectProvider(ChangedProviderId)
            firstViewModel.updateCustomBaseUrl("  $ChangedCustomUrl  ")

            assertTrue(firstViewModel.persistConfigurationBeforeExit())
            val recreatedViewModel = AssetProviderViewModel(settingsRepository, FakeGeoAssetRepository())
            backgroundScope.launch { recreatedViewModel.uiState.collect() }
            runCurrent()

            assertEquals(ChangedProviderId, recreatedViewModel.uiState.value.providerId)
            assertEquals(ChangedCustomUrl, recreatedViewModel.uiState.value.customBaseUrl)
            assertFalse(recreatedViewModel.uiState.value.hasPersistenceError)
        }

    @Test
    fun `exit cancels active import rejects duplicate and flushes latest draft`() =
        runTest {
            val settingsRepository = BlockingAppSettingsRepository(configuredSettingsRepository().snapshot())
            val operationStarted = CompletableDeferred<Unit>()
            val operationCancelled = CompletableDeferred<Unit>()
            val repository = FakeGeoAssetRepository()
            repository.importAction = {
                operationStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    operationCancelled.complete(Unit)
                }
            }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.selectProvider(ChangedProviderId)
            viewModel.updateCustomBaseUrl("  $ChangedCustomUrl  ")
            settingsRepository.updateStarted.await()
            viewModel.importLocalAsset(GeoAssetKind.Geosite, Uri.parse("content://documents/geosite.db"))
            operationStarted.await()
            val exit = async { viewModel.persistConfigurationBeforeExit() }
            runCurrent()

            assertFalse(exit.isCompleted)
            assertTrue(viewModel.uiState.value.isExitPersistenceInProgress)
            assertFalse(viewModel.persistConfigurationBeforeExit())
            operationCancelled.await()

            settingsRepository.continueUpdate.complete(Unit)
            assertTrue(exit.await())
            advanceUntilIdle()

            assertEquals(ChangedProviderId, settingsRepository.snapshot().geoAssetProviderId)
            assertEquals(ChangedCustomUrl, settingsRepository.snapshot().geoAssetCustomBaseUrl)
            assertNull(viewModel.uiState.value.activeOperation)
            assertFalse(viewModel.uiState.value.isExitPersistenceInProgress)
            assertNull(viewModel.uiState.value.lastResult)
        }

    @Test
    fun `exit cancels an active check without committing its metadata`() =
        runTest {
            val settingsRepository = configuredSettingsRepository()
            val checkStarted = CompletableDeferred<Unit>()
            val checkCancelled = CompletableDeferred<Unit>()
            val repository = FakeGeoAssetRepository()
            repository.checkAction = {
                checkStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    checkCancelled.complete(Unit)
                }
            }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.checkForUpdates()
            checkStarted.await()
            assertTrue(viewModel.persistConfigurationBeforeExit())
            advanceUntilIdle()
            checkCancelled.await()

            assertEquals("", settingsRepository.snapshot().geoAssetGeoipVersionTag)
            assertEquals("", settingsRepository.snapshot().geoAssetGeositeVersionTag)
            assertNull(viewModel.uiState.value.activeOperation)
            assertNull(viewModel.uiState.value.lastResult)
        }

    @Test
    fun `exit waits for non cooperative operation finalizer before reporting persistence failure`() =
        runTest {
            val initialSettings = configuredSettingsRepository().snapshot()
            val settingsRepository = FailingAppSettingsRepository(initialSettings, failuresRemaining = Int.MAX_VALUE)
            val operationStarted = CompletableDeferred<Unit>()
            val releaseOperation = CompletableDeferred<Unit>()
            val firstUri = Uri.parse("content://documents/first-geosite.db")
            val secondUri = Uri.parse("content://documents/second-geosite.db")
            val repository = FakeGeoAssetRepository()
            repository.importAction = {
                operationStarted.complete(Unit)
                withContext(NonCancellable) { releaseOperation.await() }
            }
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.updateCustomBaseUrl(ChangedCustomUrl)
            advanceUntilIdle()
            viewModel.importLocalAsset(GeoAssetKind.Geosite, firstUri)
            operationStarted.await()

            val exit = async { viewModel.persistConfigurationBeforeExit() }
            runCurrent()

            assertFalse(exit.isCompleted)
            assertEquals(AssetProviderOperation.ImportGeosite, viewModel.uiState.value.activeOperation)
            assertTrue(viewModel.uiState.value.isExitPersistenceInProgress)

            viewModel.importLocalAsset(GeoAssetKind.Geosite, secondUri)
            runCurrent()

            assertEquals(GeoAssetKind.Geosite to firstUri, repository.lastImport)
            assertEquals(AssetProviderOperation.ImportGeosite, viewModel.uiState.value.activeOperation)

            releaseOperation.complete(Unit)
            advanceUntilIdle()

            assertFalse(exit.await())
            assertNull(viewModel.uiState.value.activeOperation)
            assertNull(viewModel.uiState.value.lastResult)
            assertTrue(viewModel.uiState.value.hasPersistenceError)
            assertFalse(viewModel.uiState.value.isExitPersistenceInProgress)
        }

    @Test
    fun `second exit still waits when first exit waiter is cancelled`() =
        runTest {
            val operationStarted = CompletableDeferred<Unit>()
            val releaseOperation = CompletableDeferred<Unit>()
            val repository = FakeGeoAssetRepository()
            repository.importAction = {
                operationStarted.complete(Unit)
                withContext(NonCancellable) { releaseOperation.await() }
            }
            val viewModel = AssetProviderViewModel(configuredSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.importLocalAsset(GeoAssetKind.Geoip, Uri.parse("content://documents/geoip.db"))
            operationStarted.await()
            val firstExit = async { viewModel.persistConfigurationBeforeExit() }
            runCurrent()

            firstExit.cancel()
            firstExit.join()
            runCurrent()
            assertEquals(AssetProviderOperation.ImportGeoip, viewModel.uiState.value.activeOperation)

            val secondExit = async { viewModel.persistConfigurationBeforeExit() }
            runCurrent()
            assertFalse(secondExit.isCompleted)

            releaseOperation.complete(Unit)
            assertTrue(secondExit.await())
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.activeOperation)
            assertNull(viewModel.uiState.value.lastResult)
        }

    @Test
    fun `check stops at configuration storage failure and reports storage`() =
        runTest {
            val initialSettings = configuredSettingsRepository().snapshot()
            val settingsRepository = FailingAppSettingsRepository(initialSettings, failuresRemaining = Int.MAX_VALUE)
            val repository = FakeGeoAssetRepository()
            val viewModel = AssetProviderViewModel(settingsRepository, repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.updateCustomBaseUrl(ChangedCustomUrl)
            viewModel.checkForUpdates()
            advanceUntilIdle()

            assertEquals(0, repository.checkCalls)
            assertEquals(
                AssetProviderCheckOutcome.Failed(AssetProviderFailureReason.Storage),
                viewModel.uiState.value.lastResult,
            )
            assertTrue(viewModel.uiState.value.hasPersistenceError)
        }

    @Test
    fun `unchecked provider result reports missing configuration`() =
        runTest {
            val repository = FakeGeoAssetRepository()
            repository.checkResult = repository.checkResult.copy(anyChecked = false)
            val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.checkForUpdates()
            advanceUntilIdle()

            assertEquals(
                AssetProviderCheckOutcome.Failed(AssetProviderFailureReason.MissingConfiguration),
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
        var observedCustomBaseUrl: String? = null
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

    private class FailingAppSettingsRepository(
        initialSettings: AppSettings,
        var failuresRemaining: Int,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(initialSettings)
        var updateCalls = 0

        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            updateCalls += 1
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IOException("settings unavailable")
            }
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
