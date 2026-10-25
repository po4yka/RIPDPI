package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import com.poyka.ripdpi.ui.logs.LogEntryMapper
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LogsViewModelFlowTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(
        timelineSource: StubDiagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ): LogsViewModel {
        val mapper =
            LogEntryMapper(FakeStringResolver(), LocalizedTimestampFormatter(RuntimeEnvironment.getApplication()))
        return LogsViewModel(
            dependencies =
                LogsViewModelDependencies(
                    serviceStateStore = serviceStateStore,
                    diagnosticsTimelineSource = timelineSource,
                    logEntryMapper = mapper,
                    logAggregatorUseCase = LogAggregatorUseCase(mapper),
                    serviceEventObserver = LogsServiceEventObserver(mapper),
                    dispatchers =
                        AppCoroutineDispatchers(
                            default = defaultDispatcher,
                            io = defaultDispatcher,
                            main = Dispatchers.Main,
                        ),
                ),
        )
    }

    @Test
    fun `counter only telemetry ticks do not reread runtime events`() =
        runTest {
            val events =
                RecordingLogInputList(
                    listOf(
                        NativeRuntimeEvent(
                            source = "proxy",
                            level = "info",
                            message = "Connected",
                            createdAt = 42L,
                        ),
                    ),
                )
            val serviceStateStore = FakeServiceStateStore()
            val snapshot =
                ServiceTelemetrySnapshot(
                    proxyTelemetry = NativeRuntimeSnapshot(source = "proxy", nativeEvents = events),
                )
            serviceStateStore.updateTelemetry(snapshot)
            val vm = createViewModel(serviceStateStore = serviceStateStore)
            vm.uiState.test {
                assertEquals(listOf("Connected"), awaitItem().logs.map { it.message })
                events.readCount = 0

                repeat(100) { tick ->
                    serviceStateStore.updateTelemetry(
                        snapshot.copy(
                            updatedAt = tick.toLong() + 1,
                            proxyTelemetry = snapshot.proxyTelemetry.copy(totalSessions = tick.toLong() + 1),
                        ),
                    )
                }

                assertEquals(0, events.readCount)
                expectNoEvents()

                serviceStateStore.updateTelemetry(
                    snapshot.copy(
                        proxyTelemetry =
                            snapshot.proxyTelemetry.copy(
                                nativeEvents = events + events.single().copy(message = "Next event", createdAt = 43L),
                            ),
                    ),
                )
                assertEquals(listOf("Connected", "Next event"), awaitItem().logs.map { it.message })
            }
        }

    @Test
    fun `fresh equal event lists are compared without remapping and changed content still arrives`() =
        runTest {
            val event = NativeRuntimeEvent(source = "proxy", level = "info", message = "Connected", createdAt = 42L)
            val events = RecordingLogInputList(listOf(event))
            val inputs = mutableListOf(events)
            val snapshot =
                ServiceTelemetrySnapshot(
                    proxyTelemetry = NativeRuntimeSnapshot(source = "proxy", nativeEvents = events),
                )
            val store = FakeServiceStateStore().apply { updateTelemetry(snapshot) }
            val vm = createViewModel(serviceStateStore = store)

            vm.uiState.test {
                assertEquals(listOf("Connected"), awaitItem().logs.map { it.message })
                events.readCount = 0

                repeat(100) { tick ->
                    val freshEvents = RecordingLogInputList(listOf(event.copy()))
                    inputs += freshEvents
                    store.updateTelemetry(
                        snapshot.copy(
                            updatedAt = tick.toLong() + 1,
                            proxyTelemetry =
                                snapshot.proxyTelemetry.copy(
                                    totalSessions = tick.toLong() + 1,
                                    nativeEvents = freshEvents,
                                ),
                        ),
                    )
                }

                assertEquals(100, events.contentComparisonCount)
                assertEquals(0, inputs.sumOf { it.readCount })
                expectNoEvents()

                store.updateTelemetry(
                    snapshot.copy(
                        proxyTelemetry =
                            snapshot.proxyTelemetry.copy(
                                nativeEvents = listOf(event.copy(message = "Changed event", createdAt = 43L)),
                            ),
                    ),
                )
                assertEquals(listOf("Changed event"), awaitItem().logs.map { it.message })
            }
        }

    @Test
    fun `progress only ticks do not reread diagnostic events`() =
        runTest {
            val event =
                DiagnosticEvent(
                    id = "persisted-1",
                    sessionId = "diag-1",
                    source = "dns",
                    level = "info",
                    message = "Probe completed",
                    createdAt = 42L,
                )
            val events = RecordingLogInputList(listOf(event))
            val progress =
                ScanProgress(
                    sessionId = "diag-1",
                    phase = "dns",
                    completedSteps = 0,
                    totalSteps = 100,
                    message = "running",
                )
            val source =
                StubDiagnosticsTimelineSource().apply {
                    nativeEvents.value = events
                    activeScanProgress.value = progress
                }
            val vm = createViewModel(source)
            vm.uiState.test {
                assertTrue(awaitItem().logs.single().isActiveSession)
                events.readCount = 0

                repeat(100) { tick ->
                    source.activeScanProgress.value = progress.copy(completedSteps = tick + 1)
                }

                assertEquals(0, events.readCount)
                expectNoEvents()
            }
        }

    @Test
    fun `runtime and diagnostic event projection uses the default worker dispatcher`() =
        runTest {
            val runtimeEvents =
                RecordingLogInputList(
                    listOf(
                        NativeRuntimeEvent(
                            source = "proxy",
                            level = "info",
                            message = "Runtime event",
                            createdAt = 42L,
                        ),
                    ),
                )
            val diagnosticEvents =
                RecordingLogInputList(
                    listOf(
                        DiagnosticEvent(
                            id = "diagnostic-1",
                            sessionId = null,
                            source = "dns",
                            level = "info",
                            message = "Diagnostic event",
                            createdAt = 43L,
                        ),
                    ),
                )
            val store =
                FakeServiceStateStore().apply {
                    updateTelemetry(
                        ServiceTelemetrySnapshot(
                            proxyTelemetry = NativeRuntimeSnapshot(source = "proxy", nativeEvents = runtimeEvents),
                        ),
                    )
                }
            val source = StubDiagnosticsTimelineSource().apply { nativeEvents.value = diagnosticEvents }
            val vm = createViewModel(source, store, Dispatchers.Default)

            vm.uiState.test {
                var state = awaitItem()
                while (state.logs.size < 2) state = awaitItem()

                assertEquals(listOf("Runtime event", "Diagnostic event"), state.logs.map { it.message })
                assertNotNull(runtimeEvents.lastReadThread)
                assertNotNull(diagnosticEvents.lastReadThread)
                assertTrue(runtimeEvents.lastReadThread !== Thread.currentThread())
                assertTrue(diagnosticEvents.lastReadThread !== Thread.currentThread())
            }
        }

    @Test
    fun `completed diagnostic session updates active filter and new events still arrive`() =
        runTest {
            val event =
                DiagnosticEvent(
                    id = "persisted-1",
                    sessionId = "diag-1",
                    source = "dns",
                    level = "info",
                    message = "Persisted event",
                    createdAt = 42L,
                )
            val source =
                StubDiagnosticsTimelineSource().apply {
                    nativeEvents.value = listOf(event)
                    activeScanProgress.value =
                        ScanProgress(
                            sessionId = "diag-1",
                            phase = "dns",
                            completedSteps = 0,
                            totalSteps = 1,
                            message = "running",
                        )
                }
            val vm = createViewModel(source)

            vm.uiState.test {
                assertTrue(awaitItem().logs.single().isActiveSession)
                vm.setActiveSessionOnly(true)
                assertEquals(1, awaitItem().filteredLogs.size)

                source.activeScanProgress.value = null
                val completed = awaitItem()
                assertFalse(completed.logs.single().isActiveSession)
                assertTrue(completed.filteredLogs.isEmpty())

                source.liveNativeEvents.value =
                    listOf(event.copy(id = "live-2", createdAt = 43L, message = "Live event"))
                val updated = awaitItem()
                assertEquals(listOf("Persisted event", "Live event"), updated.logs.map { it.message })
                assertEquals(listOf("Live event"), updated.filteredLogs.map { it.message })
            }
        }

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
    fun `continuous diagnostics source emits new events without refresh`() =
        runTest {
            val timelineSource = StubDiagnosticsTimelineSource()
            val vm = createViewModel(timelineSource)
            vm.uiState.test {
                assertTrue(awaitItem().logs.isEmpty())
                val first =
                    DiagnosticEvent(
                        id = "live-first",
                        sessionId = "diag-live",
                        source = "dns",
                        level = "info",
                        message = "First live event",
                        createdAt = 42L,
                        subsystem = "diagnostics",
                    )
                timelineSource.liveNativeEvents.value = listOf(first)
                assertEquals(listOf("First live event"), awaitItem().logs.map { it.message })

                timelineSource.liveNativeEvents.value =
                    listOf(first, first.copy(id = "live-second", message = "Second live event", createdAt = 43L))
                assertEquals(listOf("First live event", "Second live event"), awaitItem().logs.map { it.message })
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
