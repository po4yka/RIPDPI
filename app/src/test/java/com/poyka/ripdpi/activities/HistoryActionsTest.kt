package com.poyka.ripdpi.activities

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryActionsTest {
    @Test
    fun `connection actions toggle filters and load detail`() =
        runTest {
            val filters = MutableStateFlow(HistoryConnectionFilterState())
            val details = MutableStateFlow(HistoryDetailState())
            val actions =
                HistoryConnectionActions(
                    mutations = HistoryMutationRunner(this),
                    connectionFilters = filters,
                    detailState = details,
                    loadConnectionDetail = { historyConnectionDetailUi(it) },
                )

            actions.setModeFilter("VPN")
            actions.setModeFilter("VPN")
            actions.setStatusFilter("Running")
            actions.setSearch("wifi")
            actions.selectConnection("connection-1")
            advanceUntilIdle()

            assertNull(filters.value.modeFilter)
            assertEquals("Running", filters.value.statusFilter)
            assertEquals("wifi", filters.value.search)
            assertEquals(
                "connection-1",
                details.value.selectedConnectionDetail
                    ?.session
                    ?.id,
            )

            actions.dismissDetail()
            assertNull(details.value.selectedConnectionDetail)
        }

    @Test
    fun `diagnostics actions toggle filters and load detail`() =
        runTest {
            val filters = MutableStateFlow(HistoryDiagnosticsFilterState())
            val details = MutableStateFlow(HistoryDetailState())
            val actions =
                HistoryDiagnosticsActions(
                    mutations = HistoryMutationRunner(this),
                    diagnosticsFilters = filters,
                    detailState = details,
                    loadSessionDetail = { historyDiagnosticsDetailUi(it) },
                )

            actions.setPathModeFilter("RAW_PATH")
            actions.setStatusFilter("completed")
            actions.setStatusFilter("completed")
            actions.setSearch("scan")
            actions.selectSession("scan-1")
            advanceUntilIdle()

            assertEquals("RAW_PATH", filters.value.pathModeFilter)
            assertNull(filters.value.statusFilter)
            assertEquals("scan", filters.value.search)
            assertEquals(
                "scan-1",
                details.value.selectedDiagnosticsDetail
                    ?.session
                    ?.id,
            )

            actions.dismissDetail()
            assertNull(details.value.selectedDiagnosticsDetail)
        }

    @Test
    fun `event actions toggle filters search and detail selection`() {
        val filters = MutableStateFlow(HistoryEventFilterState())
        val details = MutableStateFlow(HistoryDetailState())
        val actions =
            HistoryEventActions(
                eventFilters = filters,
                detailState = details,
            )

        actions.toggleFilter(source = "Proxy")
        actions.toggleFilter(source = "Proxy", severity = "WARN")
        actions.setSearch("route")
        actions.setAutoScroll(false)
        actions.selectEvent("event-1")

        assertNull(filters.value.sourceFilter)
        assertEquals("WARN", filters.value.severityFilter)
        assertEquals("route", filters.value.search)
        assertEquals(false, filters.value.autoScroll)
        assertEquals("event-1", details.value.selectedEventId)

        actions.dismissDetail()
        assertNull(details.value.selectedEventId)
    }
}
