package com.poyka.ripdpi.ui.screens.history

import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.SelectiveMatrixComparison
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict
import com.poyka.ripdpi.diagnostics.SelectiveMatrixRevisions
import com.poyka.ripdpi.ui.screens.diagnostics.state.loadSessionDetail
import kotlinx.coroutines.CompletableDeferred
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
                    scope = this,
                    connectionFilters = filters,
                    detailState = details,
                    loadConnectionDetail = { historyConnectionDetail(it) },
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
                    scope = this,
                    diagnosticsFilters = filters,
                    detailState = details,
                    loadSessionDetail = { historyDiagnosticsDetail(it) },
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
    fun `comparing two matrix sessions stores the pair and selecting another session clears it`() =
        runTest {
            val details = MutableStateFlow(HistoryDetailState())
            val sessions =
                mapOf(
                    "wifi" to matrixDetail("wifi", "wifi", startedAt = 0L, available = true),
                    "cellular" to matrixDetail("cellular", "cellular", startedAt = 60_000L, available = false),
                )
            val actions =
                HistoryDiagnosticsActions(
                    scope = this,
                    diagnosticsFilters = MutableStateFlow(HistoryDiagnosticsFilterState()),
                    detailState = details,
                    loadSessionDetail = { sessions[it] },
                )

            actions.selectSession("cellular")
            advanceUntilIdle()
            actions.compareMatrixSession("wifi")
            advanceUntilIdle()

            val pair = requireNotNull(details.value.matrixPair)
            assertEquals("wifi", pair.pairedSessionId)
            val compared = pair.comparison as SelectiveMatrixComparison.Compared
            // Legs are ordered by start time, whichever session was opened first.
            assertEquals("wifi" to "cellular", compared.first.transport to compared.second.transport)
            assertEquals(
                SelectiveMatrixPairVerdict.PATH_SPECIFIC,
                compared.rows.single { it.targetId == "site" }.verdict,
            )

            actions.selectSession("wifi")
            advanceUntilIdle()
            assertNull(details.value.matrixPair)

            actions.compareMatrixSession("cellular")
            advanceUntilIdle()
            actions.dismissDetail()
            assertNull(details.value.matrixPair)
        }

    @Test
    fun `a comparison never drops a selection that is still loading`() =
        runTest {
            val details = MutableStateFlow(HistoryDetailState())
            val plainLoaded = CompletableDeferred<Unit>()
            val sessions =
                mapOf(
                    "wifi" to matrixDetail("wifi", "wifi", startedAt = 0L, available = true),
                    "cellular" to matrixDetail("cellular", "cellular", startedAt = 60_000L, available = false),
                )
            val actions =
                HistoryDiagnosticsActions(
                    scope = this,
                    diagnosticsFilters = MutableStateFlow(HistoryDiagnosticsFilterState()),
                    detailState = details,
                    loadSessionDetail = { id ->
                        if (id == "plain") plainLoaded.await()
                        sessions[id] ?: historyDiagnosticsDetail(id)
                    },
                )
            actions.selectSession("cellular")
            advanceUntilIdle()

            actions.selectSession("plain")
            actions.compareMatrixSession("wifi")
            advanceUntilIdle()
            plainLoaded.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                "plain",
                details.value.selectedDiagnosticsDetail
                    ?.session
                    ?.id,
            )
            assertNull(details.value.matrixPair)
        }

    @Test
    fun `pairing offers other matrix sessions with revisions and labels targets from the open session`() {
        val open = matrixDetail("open", "wifi", startedAt = 0L, available = true)
        val sessions =
            listOf(
                open.session,
                matrixDetail("other", "cellular", startedAt = 1L, available = true).session,
                historyScanSession(id = "legacy"),
            )

        val pairing = requireNotNull(selectiveMatrixPairing(open, sessions, emptyList(), pair = null))

        assertEquals(listOf("other"), pairing.candidates.map { it.sessionId })
        val nearestFirst =
            selectiveMatrixPairing(
                open,
                listOf(5L, 1L, 3L).map { matrixDetail("s$it", "cellular", startedAt = it, available = true).session },
                emptyList(),
                pair = null,
            )
        assertEquals(listOf("s1", "s3", "s5"), nearestFirst?.candidates?.map { it.sessionId })
        assertEquals("cellular", pairing.candidates.single().transport)
        assertEquals("example.org", pairing.targetLabels["site"])
        assertNull(selectiveMatrixPairing(historyDiagnosticsDetail("plain"), sessions, emptyList(), pair = null))
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

    @Test
    fun `clear filters resets search and chips for every history section`() =
        runTest {
            val connectionFilters =
                MutableStateFlow(
                    HistoryConnectionFilterState(modeFilter = "VPN", statusFilter = "Running", search = "wifi"),
                )
            val diagnosticsFilters =
                MutableStateFlow(
                    HistoryDiagnosticsFilterState(
                        pathModeFilter = "RAW_PATH",
                        statusFilter = "completed",
                        search = "scan",
                    ),
                )
            val eventFilters =
                MutableStateFlow(
                    HistoryEventFilterState(sourceFilter = "Proxy", severityFilter = "WARN", search = "route"),
                )
            val details = MutableStateFlow(HistoryDetailState())

            HistoryConnectionActions(
                scope = this,
                connectionFilters = connectionFilters,
                detailState = details,
                loadConnectionDetail = { null },
            ).clearFilters()
            HistoryDiagnosticsActions(
                scope = this,
                diagnosticsFilters = diagnosticsFilters,
                detailState = details,
                loadSessionDetail = { null },
            ).clearFilters()
            HistoryEventActions(eventFilters = eventFilters, detailState = details).clearFilters()

            assertEquals(HistoryConnectionFilterState(), connectionFilters.value)
            assertEquals(HistoryDiagnosticsFilterState(), diagnosticsFilters.value)
            assertEquals(HistoryEventFilterState(), eventFilters.value)
        }
}

private fun matrixDetail(
    id: String,
    transport: String,
    startedAt: Long,
    available: Boolean,
): DiagnosticSessionDetail {
    fun attempt(
        targetId: String,
        cohort: String,
        outcome: String,
    ) = ProbeResult(
        probeType = "selective_availability",
        target = if (targetId == "site") "example.org" else "control.example",
        outcome = outcome,
        details =
            listOfNotNull(
                ProbeDetail("targetId", targetId),
                ProbeDetail("cohort", cohort),
                ProbeDetail("failureStage", "tls").takeIf { outcome == "matrix_target_transport_failed" },
            ),
    )
    val siteOutcome = if (available) "matrix_target_available" else "matrix_target_transport_failed"
    val results =
        List(2) { attempt("control", "declared_available", "matrix_target_available") } +
            List(2) { attempt("site", "global", siteOutcome) } +
            ProbeResult(
                "selective_availability_summary",
                "matrix",
                "matrix_mixed",
                listOf(ProbeDetail("repetitionsPerTarget", "2")),
            )
    return historyDiagnosticsDetail(id).copy(
        session =
            historyScanSession(id = id, startedAt = startedAt).copy(
                networkTransport = transport,
                matrixRevisions = SelectiveMatrixRevisions("targets", "probe", "config"),
            ),
        results = results,
    )
}
