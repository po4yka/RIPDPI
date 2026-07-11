package com.poyka.ripdpi.ui.screens.settings

import android.net.Uri
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.assets.GeoAssetIntegrityException
import com.poyka.ripdpi.assets.GeoAssetRepository
import com.poyka.ripdpi.assets.GeoAssetUpdateResult
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            assertNull(viewModel.uiState.value.lastResult)

            completion.complete(Unit)
            advanceUntilIdle()
            assertEquals(AssetProviderCheckOutcome.Imported, viewModel.uiState.value.lastResult)
        }

    @Test
    fun `integrity failure becomes failed outcome with existing message`() =
        runTest {
            val repository = FakeGeoAssetRepository()
            repository.importAction = {
                throw GeoAssetIntegrityException("Imported geo asset failed the validity gate.")
            }
            val viewModel = AssetProviderViewModel(FakeAppSettingsRepository(), repository)
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            viewModel.importLocalAsset(GeoAssetKind.Geoip, Uri.parse("content://documents/invalid.db"))
            advanceUntilIdle()

            assertEquals(
                AssetProviderCheckOutcome.Failed("Imported geo asset failed the validity gate."),
                viewModel.uiState.value.lastResult,
            )
        }

    private class FakeGeoAssetRepository : GeoAssetRepository {
        var lastImport: Pair<GeoAssetKind, Uri>? = null
        var importAction: suspend () -> Unit = {}

        override suspend fun checkAndUpdate(): GeoAssetUpdateResult = error("not used")

        override suspend fun importLocalAsset(
            kind: GeoAssetKind,
            uri: Uri,
        ) {
            lastImport = kind to uri
            importAction()
        }
    }
}
