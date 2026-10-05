package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsExportPreviewEntryPointTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `diagnostic summary request cannot hand off externally before preview confirmation`() =
        runTest {
            val manager = FakeDiagnosticsManager()
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            viewModel.effects.test {
                viewModel.shareSummary()
                assertTrue(
                    "Summary escaped before the user saw and confirmed prepared content",
                    awaitItem() is DiagnosticsEffect.PrepareExportRequested,
                )
            }
        }
}
