package com.poyka.ripdpi.ui.screens.support

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.support.SupportSettingsApplyUseCase
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SupportSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `preview storage failure exits loading and same package can retry`() =
        runTest {
            val repository = FakeSettingsRepository().apply { readFailure = IOException("private path") }
            val viewModel = SupportSettingsViewModel(SupportSettingsApplyUseCase(repository))
            val packageJson = """{"schema":1,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}"""
            viewModel.setPackage(packageJson)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.loading)
            assertTrue(viewModel.uiState.value.storageError)
            assertFalse(viewModel.uiState.value.invalid)
            assertEquals(null, viewModel.uiState.value.preview)
            repository.readFailure = null
            viewModel.setPackage(packageJson)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.preview != null)
            assertFalse(viewModel.uiState.value.storageError)
        }

    @Test
    fun `apply storage failure preserves preview and can retry without false completion`() =
        runTest {
            val repository = FakeSettingsRepository()
            val viewModel = SupportSettingsViewModel(SupportSettingsApplyUseCase(repository))
            viewModel.setPackage(
                """{"schema":1,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}""",
            )
            advanceUntilIdle()
            repository.writeFailure = IOException("private path")
            viewModel.appliedEvents.test {
                viewModel.apply()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.applying)
                assertTrue(viewModel.uiState.value.storageError)
                assertTrue(viewModel.uiState.value.preview != null)
                expectNoEvents()
                repository.writeFailure = null
                viewModel.apply()
                advanceUntilIdle()
                awaitItem()
            }
            assertEquals("dark", repository.snapshot().appTheme)
            assertFalse(viewModel.uiState.value.storageError)
        }

    @Test
    fun `successful apply emits one completion event and cannot replay`() =
        runTest {
            val repository = FakeSettingsRepository()
            val viewModel = SupportSettingsViewModel(SupportSettingsApplyUseCase(repository))
            viewModel.setPackage(
                """{"schema":1,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}""",
            )
            advanceUntilIdle()

            viewModel.appliedEvents.test {
                viewModel.apply()
                advanceUntilIdle()
                awaitItem()
                viewModel.apply()
                advanceUntilIdle()
                expectNoEvents()
            }

            assertEquals("dark", repository.snapshot().appTheme)
            assertEquals(1, repository.writeCalls)
        }
}

private class FakeSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettings.getDefaultInstance())
    var readFailure: IOException? = null
    var writeFailure: IOException? = null
    var writeCalls = 0
        private set

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings {
        readFailure?.let { throw it }
        return state.value
    }

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        writeFailure?.let { throw it }
        writeCalls += 1
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        writeFailure?.let { throw it }
        writeCalls += 1
        state.value = settings
    }
}
