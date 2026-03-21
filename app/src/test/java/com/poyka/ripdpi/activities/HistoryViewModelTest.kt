package com.poyka.ripdpi.activities

import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val coreSupport = DiagnosticsUiCoreSupport()
    private val connectionDetailUiFactory = HistoryConnectionDetailUiFactory(coreSupport)
    private val uiStateFactory =
        HistoryUiStateFactory(
            coreSupport = coreSupport,
            connectionDetailUiFactory = connectionDetailUiFactory,
        )

    @Test
    fun `initializer runs once and timeline emissions rebuild ui state`() =
        runTest {
            val timeline = FakeHistoryTimelineDataSource().apply {
                connectionSessions.value = listOf(historyConnectionSession(id = "connection-1"))
                diagnosticsSessions.value = listOf(historyScanSession(id = "scan-1"))
                nativeEvents.value = emptyList()
            }
            val detailLoader = FakeHistoryDetailLoader()
            val initializer = RecordingHistoryInitializer()
            val viewModel =
                HistoryViewModel(
                    historyTimelineDataSource = timeline,
                    historyDetailLoader = detailLoader,
                    historyInitializer = initializer,
                    historyUiStateFactory = uiStateFactory,
                )

            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(0, initializer.calls)
            viewModel.initialize()
            viewModel.initialize()
            advanceUntilIdle()

            assertEquals(1, initializer.calls)
            assertEquals(listOf("connection-1"), viewModel.uiState.value.connections.sessions.map { it.id })
            assertEquals(listOf("scan-1"), viewModel.uiState.value.diagnostics.sessions.map { it.id })

            timeline.nativeEvents.value = listOf(historyEvent(id = "event-1"))
            advanceUntilIdle()

            assertEquals(listOf("event-1"), viewModel.uiState.value.events.events.map { it.id })
            collector.cancel()
        }

    @Test
    fun `selecting history details uses the injected loader`() =
        runTest {
            val timeline = FakeHistoryTimelineDataSource().apply {
                connectionSessions.value = listOf(historyConnectionSession(id = "connection-1"))
                diagnosticsSessions.value = listOf(historyScanSession(id = "scan-1"))
            }
            val detailLoader =
                FakeHistoryDetailLoader().apply {
                    connectionDetails["connection-1"] = historyConnectionDetailUi("connection-1")
                    diagnosticsDetails["scan-1"] = historyDiagnosticsDetailUi("scan-1")
                }
            val viewModel =
                HistoryViewModel(
                    historyTimelineDataSource = timeline,
                    historyDetailLoader = detailLoader,
                    historyInitializer = RecordingHistoryInitializer(),
                    historyUiStateFactory = uiStateFactory,
                )

            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.initialize()
            advanceUntilIdle()

            viewModel.selectConnection("connection-1")
            viewModel.selectDiagnosticsSession("scan-1")
            advanceUntilIdle()

            assertEquals("connection-1", viewModel.uiState.value.selectedConnectionDetail?.session?.id)
            assertEquals("scan-1", viewModel.uiState.value.selectedDiagnosticsDetail?.session?.id)
            collector.cancel()
        }
}
