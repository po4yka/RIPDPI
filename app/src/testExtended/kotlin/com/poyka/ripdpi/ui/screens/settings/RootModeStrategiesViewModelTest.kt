package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RootAccessResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RootModeStrategiesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a failed settings write does not crash or start the root check`() =
        runTest {
            var checks = 0
            val failingRepository =
                object : AppSettingsRepository by FakeAppSettingsRepository() {
                    override suspend fun update(transform: AppSettings.Builder.() -> Unit) =
                        throw IOException("No space left on device")
                }
            val viewModel =
                RootModeStrategiesViewModel(failingRepository) {
                    checks++
                    RootAccessResult.Granted
                }
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.setRootModeEnabled(true)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.rootModeEnabled)
            assertEquals(RootAccessState.NotChecked, viewModel.uiState.value.rootAccess)
            assertEquals(0, checks)
        }

    @Test
    fun `root mode choice is persisted`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val viewModel = RootModeStrategiesViewModel(repository) { RootAccessResult.Granted }
            assertFalse(repository.snapshot().rootModeEnabled)
            viewModel.setRootModeEnabled(true)
            advanceUntilIdle()
            assertTrue(repository.snapshot().rootModeEnabled)
            viewModel.setRootModeEnabled(false)
            advanceUntilIdle()
            assertFalse(repository.snapshot().rootModeEnabled)
        }

    @Test
    fun `turning root mode on reports root availability and turning it off clears it`() =
        runTest {
            var checks = 0
            val viewModel =
                RootModeStrategiesViewModel(FakeAppSettingsRepository()) {
                    checks++
                    RootAccessResult.Unavailable
                }
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(RootAccessState.NotChecked, viewModel.uiState.value.rootAccess)
            assertEquals(0, checks)

            viewModel.setRootModeEnabled(true)
            advanceUntilIdle()
            assertEquals(RootAccessState.Unavailable, viewModel.uiState.value.rootAccess)
            assertEquals(1, checks)

            viewModel.setRootModeEnabled(false)
            advanceUntilIdle()
            assertEquals(RootAccessState.NotChecked, viewModel.uiState.value.rootAccess)
            assertEquals(1, checks)
        }

    @Test
    fun `a root check that throws reports root as unavailable instead of checking`() =
        runTest {
            val viewModel =
                RootModeStrategiesViewModel(FakeAppSettingsRepository()) {
                    throw SecurityException("su process start denied")
                }
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.setRootModeEnabled(true)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.rootModeEnabled)
            assertEquals(RootAccessState.Unavailable, viewModel.uiState.value.rootAccess)
        }

    @Test
    fun `a check that finishes after root mode is turned off does not report a result`() =
        runTest {
            val pendingCheck = CompletableDeferred<RootAccessResult>()
            val viewModel = RootModeStrategiesViewModel(FakeAppSettingsRepository()) { pendingCheck.await() }
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.setRootModeEnabled(true)
            advanceUntilIdle()
            assertEquals(RootAccessState.Checking, viewModel.uiState.value.rootAccess)

            viewModel.setRootModeEnabled(false)
            advanceUntilIdle()
            pendingCheck.complete(RootAccessResult.Granted)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.rootModeEnabled)
            assertEquals(RootAccessState.NotChecked, viewModel.uiState.value.rootAccess)
        }
}
