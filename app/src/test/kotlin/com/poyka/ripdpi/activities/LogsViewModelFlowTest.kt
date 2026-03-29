package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogsViewModelFlowTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(timelineSource: StubDiagnosticsTimelineSource = StubDiagnosticsTimelineSource()) =
        LogsViewModel(FakeServiceStateStore(), timelineSource, FakeStringResolver())

    @Test
    fun `initial uiState has empty logs and all filters active`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                val state = awaitItem()
                assertTrue(state.logs.isEmpty())
                assertEquals(LogSubsystem.entries.toSet(), state.activeSubsystems)
                assertEquals(LogSeverity.entries.toSet(), state.activeSeverities)
                assertFalse(state.showActiveSessionOnly)
                assertTrue(state.isAutoScroll)
            }
        }

    @Test
    fun `appendLog adds manual entry to uiState`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.appendLog("test message", LogType.DNS)
                val state = awaitItem()
                assertEquals(1, state.logs.size)
                assertEquals("test message", state.logs[0].message)
                assertEquals(LogSubsystem.Diagnostics, state.logs[0].subsystem)
            }
        }

    @Test
    fun `clearLogs hides earlier entries`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.appendLog("entry", LogType.CONN)
                awaitItem()
                vm.clearLogs()
                val state = awaitItem()
                assertTrue(state.logs.isEmpty())
            }
        }

    @Test
    fun `toggleSubsystemFilter removes and re-adds a filter`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.toggleSubsystemFilter(LogSubsystem.Diagnostics)
                val withoutDiagnostics = awaitItem()
                assertFalse(LogSubsystem.Diagnostics in withoutDiagnostics.activeSubsystems)

                vm.toggleSubsystemFilter(LogSubsystem.Diagnostics)
                val withDiagnostics = awaitItem()
                assertTrue(LogSubsystem.Diagnostics in withDiagnostics.activeSubsystems)
            }
        }

    @Test
    fun `toggleSeverityFilter removes and re-adds a filter`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.toggleSeverityFilter(LogSeverity.Warn)
                val withoutWarn = awaitItem()
                assertFalse(LogSeverity.Warn in withoutWarn.activeSeverities)

                vm.toggleSeverityFilter(LogSeverity.Warn)
                val withWarn = awaitItem()
                assertTrue(LogSeverity.Warn in withWarn.activeSeverities)
            }
        }

    @Test
    fun `setters update auto-scroll and active-session flags`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.setAutoScroll(false)
                assertFalse(awaitItem().isAutoScroll)
                vm.setActiveSessionOnly(true)
                assertTrue(awaitItem().showActiveSessionOnly)
            }
        }

    @Test
    fun `diagnostics events are merged into logs and marked active when session matches`() =
        runTest {
            val timelineSource =
                StubDiagnosticsTimelineSource().apply {
                    activeScanProgress.value =
                        ScanProgress(
                            sessionId = "diag-1",
                            phase = "dns",
                            completedSteps = 1,
                            totalSteps = 2,
                            message = "running",
                        )
                    liveNativeEvents.value =
                        listOf(
                            DiagnosticEvent(
                                id = "event-1",
                                sessionId = "diag-1",
                                source = "dns",
                                level = "warn",
                                message = "probe failed target=example.org",
                                createdAt = 42L,
                                runtimeId = "vpn-runtime-1",
                                subsystem = "diagnostics",
                            ),
                        )
                }
            val vm = createViewModel(timelineSource)

            vm.uiState.test {
                val state = awaitItem()
                assertEquals(1, state.logs.size)
                assertEquals(LogSubsystem.Diagnostics, state.logs.first().subsystem)
                assertEquals(LogSeverity.Warn, state.logs.first().severity)
                assertTrue(state.logs.first().isActiveSession)
                assertEquals("diag-1", state.logs.first().diagnosticsSessionId)
            }
        }

    @Test
    fun `log buffer respects max capacity`() =
        runTest {
            val vm = createViewModel()
            repeat(251) { i ->
                vm.appendLog("log $i", LogType.CONN)
            }
            vm.uiState.test {
                val state = awaitItem()
                assertEquals(250, state.logs.size)
                assertEquals("log 1", state.logs.first().message)
                assertEquals("log 250", state.logs.last().message)
            }
        }
}
