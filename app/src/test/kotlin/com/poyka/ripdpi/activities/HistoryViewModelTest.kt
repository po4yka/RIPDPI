package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val coreSupport = testDiagnosticsUiCoreSupport()
    private val connectionDetailUiFactory =
        HistoryConnectionDetailUiFactory(
            stringResolver = AndroidStringResolver(ApplicationProvider.getApplicationContext()),
            coreSupport = coreSupport,
        )
    private val uiStateFactory =
        HistoryUiStateFactory(
            coreSupport = coreSupport,
            diagnosticsSessionDetailUiMapper = testHistorySessionDetailUiMapper(coreSupport),
            connectionDetailUiFactory = connectionDetailUiFactory,
        )

    @Test
    fun `initializer runs once and timeline emissions rebuild ui state`() =
        runTest {
            val timeline =
                FakeDiagnosticsHistorySource().apply {
                    connectionSessions.value = listOf(historyConnectionSession(id = "connection-1"))
                    diagnosticsSessions.value = listOf(historyScanSession(id = "scan-1"))
                    nativeEvents.value = emptyList()
                }
            val detailLoader = FakeHistoryDetailLoader()
            val diagnosticsBootstrapper = FakeHistoryDiagnosticsBootstrapper()
            val viewModel =
                HistoryViewModel(
                    diagnosticsHistorySource = timeline,
                    historyDetailLoader = detailLoader,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    historyUiStateFactory = uiStateFactory,
                )

            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(0, diagnosticsBootstrapper.initializeCalls)
            viewModel.initialize()
            viewModel.initialize()
            advanceUntilIdle()

            assertEquals(1, diagnosticsBootstrapper.initializeCalls)
            assertEquals(
                listOf("connection-1"),
                viewModel.uiState.value.connections.sessions
                    .map { it.id },
            )
            assertEquals(
                listOf("scan-1"),
                viewModel.uiState.value.diagnostics.sessions
                    .map { it.id },
            )

            timeline.nativeEvents.value = listOf(historyEvent(id = "event-1"))
            advanceUntilIdle()

            assertEquals(
                listOf("event-1"),
                viewModel.uiState.value.events.events
                    .map { it.id },
            )
            collector.cancel()
        }

    @Test
    fun `ui state stays loading until the first repository snapshot arrives`() =
        runTest {
            val viewModel =
                HistoryViewModel(
                    diagnosticsHistorySource = FakeDiagnosticsHistorySource(),
                    historyDetailLoader = FakeHistoryDetailLoader(),
                    diagnosticsBootstrapper = FakeHistoryDiagnosticsBootstrapper(),
                    historyUiStateFactory = uiStateFactory,
                )

            assertTrue(viewModel.uiState.value.isLoading)

            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.hasConnectionHistory)
            collector.cancel()
        }

    @Test
    fun `selecting history details uses the injected loader`() =
        runTest {
            val timeline =
                FakeDiagnosticsHistorySource().apply {
                    connectionSessions.value = listOf(historyConnectionSession(id = "connection-1"))
                    diagnosticsSessions.value = listOf(historyScanSession(id = "scan-1"))
                }
            val detailLoader =
                FakeHistoryDetailLoader().apply {
                    connectionDetails["connection-1"] = historyConnectionDetail("connection-1")
                    diagnosticsDetails["scan-1"] = historyDiagnosticsDetail("scan-1")
                }
            val viewModel =
                HistoryViewModel(
                    diagnosticsHistorySource = timeline,
                    historyDetailLoader = detailLoader,
                    diagnosticsBootstrapper = FakeHistoryDiagnosticsBootstrapper(),
                    historyUiStateFactory = uiStateFactory,
                )

            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.initialize()
            advanceUntilIdle()

            viewModel.selectConnection("connection-1")
            viewModel.selectDiagnosticsSession("scan-1")
            advanceUntilIdle()

            assertEquals(
                "connection-1",
                viewModel.uiState.value.selectedConnectionDetail
                    ?.session
                    ?.id,
            )
            assertEquals(
                "scan-1",
                viewModel.uiState.value.selectedDiagnosticsDetail
                    ?.session
                    ?.id,
            )
            collector.cancel()
        }
}
