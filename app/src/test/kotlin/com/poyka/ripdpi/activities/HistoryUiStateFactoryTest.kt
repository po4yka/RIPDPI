package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryUiStateFactoryTest {
    private val factory =
        HistoryUiStateFactory(
            coreSupport = DiagnosticsUiCoreSupport(),
            connectionDetailUiFactory = HistoryConnectionDetailUiFactory(DiagnosticsUiCoreSupport()),
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
}
