package com.poyka.ripdpi.activities

import app.cash.turbine.test
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

    private fun createViewModel() = LogsViewModel(FakeServiceStateStore(), FakeStringResolver())

    @Test
    fun `initial uiState has empty logs and all filters active`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                val state = awaitItem()
                assertTrue(state.logs.isEmpty())
                assertEquals(LogType.entries.toSet(), state.activeFilters)
                assertTrue(state.isAutoScroll)
            }
        }

    @Test
    fun `appendLog adds entry to uiState`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.appendLog("test message", LogType.DNS)
                val state = awaitItem()
                assertEquals(1, state.logs.size)
                assertEquals(LogType.DNS, state.logs[0].type)
                assertEquals("test message", state.logs[0].message)
            }
        }

    @Test
    fun `appendLog auto-classifies log type`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.appendLog("DNS resolver started")
                val state = awaitItem()
                assertEquals(LogType.DNS, state.logs[0].type)
            }
        }

    @Test
    fun `clearLogs empties the buffer`() =
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
    fun `toggleFilter removes and re-adds a filter`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.toggleFilter(LogType.DNS)
                val withoutDns = awaitItem()
                assertFalse(LogType.DNS in withoutDns.activeFilters)

                vm.toggleFilter(LogType.DNS)
                val withDns = awaitItem()
                assertTrue(LogType.DNS in withDns.activeFilters)
            }
        }

    @Test
    fun `setAutoScroll updates isAutoScroll`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.setAutoScroll(false)
                assertFalse(awaitItem().isAutoScroll)
            }
        }

    @Test
    fun `filteredLogs reflects active filters`() =
        runTest {
            val vm = createViewModel()
            vm.appendLog("dns entry", LogType.DNS)
            vm.appendLog("error entry", LogType.ERR)

            vm.uiState.test {
                val initial = awaitItem()
                assertEquals(2, initial.filteredLogs.size)

                vm.toggleFilter(LogType.DNS)
                val filtered = awaitItem()
                assertEquals(1, filtered.filteredLogs.size)
                assertEquals(LogType.ERR, filtered.filteredLogs[0].type)
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
