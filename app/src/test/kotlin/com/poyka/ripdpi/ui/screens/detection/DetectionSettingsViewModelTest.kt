package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `typed text shows at once while the DataStore write is still pending`() =
        runTest {
            val repository = GatedSettingsRepository()
            val viewModel = DetectionSettingsViewModel(repository)

            viewModel.setDnsDirectServers("9")
            viewModel.setDnsDirectServers("9.")

            assertEquals("9.", viewModel.uiState.value.dnsDirectServers)

            repository.release()
            advanceUntilIdle()
            assertEquals("9.", viewModel.uiState.value.dnsDirectServers)
            assertEquals("9.", repository.current.detectionCheckDnsDirectServers)
        }

    @Test
    fun `a cleared port field stays blank instead of snapping back to the default`() =
        runTest {
            val repository = GatedSettingsRepository()
            val viewModel = DetectionSettingsViewModel(repository)
            repository.release()

            viewModel.setCustomPortStart("")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("", state.customPortStartText)
            assertFalse(state.customPortRangeValid)
        }

    /** Holds every update until [release], like a slow DataStore write. */
    private class GatedSettingsRepository : AppSettingsRepository {
        private val state = MutableStateFlow(AppSettings.getDefaultInstance())
        private val gate = CompletableDeferred<Unit>()

        val current: AppSettings
            get() = state.value

        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            gate.await()
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
        }

        fun release() {
            gate.complete(Unit)
        }
    }
}
