package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class RootModeStrategiesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

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
