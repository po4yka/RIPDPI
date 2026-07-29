package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.ScanTerminationReason
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class HistoryUiStateFactoryTest {
    private val factory =
        HistoryUiStateFactory(
            coreSupport = testDiagnosticsUiCoreSupport(),
            connectionDetailUiFactory =
                HistoryConnectionDetailUiFactory(
                    context = ApplicationProvider.getApplicationContext(),
                    stringResolver = AndroidStringResolver(ApplicationProvider.getApplicationContext()),
                    coreSupport = testDiagnosticsUiCoreSupport(),
                ),
        )

    @Test
    fun `buildUiState filters rows and preserves focused selections`() {
        val snapshot =
            HistoryRepositorySnapshot(
                connectionSessions =
                    listOf(
                        historyConnectionSession(id = "connection-1", mode = "VPN", state = "Running"),
                        historyConnectionSession(id = "connection-2", mode = "Proxy", state = "Failed"),
                    ),
                scanSessions =
                    listOf(
                        historyScanSession(id = "scan-1", pathMode = "RAW_PATH", status = "completed"),
                        historyScanSession(id = "scan-2", pathMode = "IN_PATH", status = "running"),
                    ),
                nativeEvents =
                    listOf(
                        historyEvent(id = "event-1", source = "proxy", level = "warn"),
                        historyEvent(id = "event-2", source = "tunnel", level = "info"),
                    ),
            )

        val state =
            factory.buildUiState(
                repositorySnapshot = snapshot,
                selectedSection = HistorySection.Diagnostics,
                connectionFilters =
                    HistoryConnectionFilterState(
                        modeFilter = "VPN",
                        statusFilter = "Running",
                        search = "wifi",
                    ),
                diagnosticsFilters =
                    HistoryDiagnosticsFilterState(
                        pathModeFilter = "RAW_PATH",
                        statusFilter = "completed",
                        search = "scan",
                    ),
                eventFilters =
                    HistoryEventFilterState(
                        sourceFilter = "Proxy",
                        severityFilter = "WARN",
                        search = "route",
                        autoScroll = false,
                    ),
                detailState =
                    HistoryDetailState(
                        selectedConnectionDetail = historyConnectionDetailUi("connection-1"),
                        selectedDiagnosticsDetail = historyDiagnosticsDetailUi("scan-1"),
                        selectedEventId = "event-2",
                    ),
            )

        assertEquals(HistorySection.Diagnostics, state.selectedSection)
        assertEquals(listOf("VPN", "Proxy"), state.connections.modes)
        assertEquals(listOf("Running", "Failed"), state.connections.statuses)
        assertEquals(listOf("connection-1"), state.connections.sessions.map { it.id })
        assertEquals("connection-1", state.connections.focusedSessionId)

        assertEquals(listOf("RAW_PATH", "IN_PATH"), state.diagnostics.pathModes)
        assertEquals(listOf("completed", "running"), state.diagnostics.statuses)
        assertEquals(listOf("scan-1"), state.diagnostics.sessions.map { it.id })
        assertEquals("scan-1", state.diagnostics.focusedSessionId)

        assertEquals(listOf("Proxy", "Tunnel"), state.events.availableSources)
        assertEquals(listOf("WARN", "INFO"), state.events.availableSeverities)
        assertEquals(listOf("event-1"), state.events.events.map { it.id })
        assertEquals("event-2", state.events.focusedEventId)
        assertEquals("event-2", state.selectedEvent?.id)
        assertTrue(
            state.events.filters.autoScroll
                .not(),
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `history localizes terminated and partial completion semantics`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val strings = AndroidStringResolver(context)
        val localizedCore = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings)
        val localizedFactory =
            HistoryUiStateFactory(
                coreSupport = localizedCore,
                connectionDetailUiFactory =
                    HistoryConnectionDetailUiFactory(
                        context = context,
                        stringResolver = strings,
                        coreSupport = localizedCore,
                    ),
            )
        val snapshot =
            HistoryRepositorySnapshot(
                scanSessions =
                    listOf(
                        historyScanSession(
                            id = "terminated",
                            summary = "Lifecycle completed",
                            report =
                                DiagnosticsSessionProjection(
                                    completionKind = ScanCompletionKind.TERMINATED,
                                    terminationReason = ScanTerminationReason.WORKER_PANICKED,
                                ),
                        ),
                        historyScanSession(
                            id = "partial",
                            summary = "Lifecycle completed",
                            report =
                                DiagnosticsSessionProjection(
                                    completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                                    terminationReason = ScanTerminationReason.DEADLINE_EXCEEDED,
                                ),
                        ),
                    ),
            )

        val rows =
            localizedFactory
                .buildUiState(
                    repositorySnapshot = snapshot,
                    selectedSection = HistorySection.Diagnostics,
                    connectionFilters = HistoryConnectionFilterState(),
                    diagnosticsFilters = HistoryDiagnosticsFilterState(),
                    eventFilters = HistoryEventFilterState(),
                    detailState = HistoryDetailState(),
                ).diagnostics.sessions
                .associateBy { it.id }

        assertEquals("Сбой рабочего процесса", rows.getValue("terminated").completionLabel)
        assertEquals("Сбой рабочего процесса", rows.getValue("terminated").summary)
        assertEquals("Частичные результаты", rows.getValue("partial").completionLabel)
        assertEquals(
            "Частичные результаты · Превышен лимит времени",
            rows.getValue("partial").summary,
        )
    }
}
