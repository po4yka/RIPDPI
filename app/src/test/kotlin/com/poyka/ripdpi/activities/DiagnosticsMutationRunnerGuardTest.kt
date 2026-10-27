package com.poyka.ripdpi.activities

import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsMutationRunnerGuardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `selecting a session whose detail can no longer load does not crash the app`() =
        runTest {
            // detail = null makes the loader throw, as for a session that retention deleted after the list rendered.
            val manager = FakeDiagnosticsManager(detail = null)
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.selectSession("deleted-session")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.selectedSessionDetail)
            collector.cancel()
        }
}
