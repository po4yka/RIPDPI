package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
            val viewModel = RootModeStrategiesViewModel(repository)
            assertFalse(repository.snapshot().rootModeEnabled)
            viewModel.setRootModeEnabled(true)
            advanceUntilIdle()
            assertTrue(repository.snapshot().rootModeEnabled)
            viewModel.setRootModeEnabled(false)
            advanceUntilIdle()
            assertFalse(repository.snapshot().rootModeEnabled)
        }
}
