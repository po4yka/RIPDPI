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
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupportSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
            assertEquals(1, repository.replaceCalls)
        }
}

private class FakeSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettings.getDefaultInstance())
    var replaceCalls = 0
        private set

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        replaceCalls += 1
        state.value = settings
    }
}
