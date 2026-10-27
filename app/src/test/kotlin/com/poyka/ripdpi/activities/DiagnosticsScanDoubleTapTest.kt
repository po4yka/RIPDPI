package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsScanDoubleTapTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a second tap while the first scan start is in flight starts nothing`() =
        runTest {
            val firstStartGate = CompletableDeferred<Unit>()
            var startCalls = 0
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.onStartScan = { pathMode, _ ->
                        startCalls += 1
                        firstStartGate.await()
                        DiagnosticsManualScanStartResult.Started("session-${pathMode.name}")
                    }
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.startRawScan()
            runCurrent()
            viewModel.startRawScan()
            runCurrent()
            firstStartGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, startCalls)
            collector.cancel()
        }
}
