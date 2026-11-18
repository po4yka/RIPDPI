package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsConnectionLaneUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsConnectionMeasurementUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsConnectionStageUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScanScopeTest {
    @Test
    fun `a slower answer lands farther from the hub and stays inside the unmeasured lane`() {
        val marks =
            scopeLayout(
                listOf(
                    probe("fast", "domain_reachability", DiagnosticsTone.Positive, elapsedMs = 40),
                    probe("slow", "domain_reachability", DiagnosticsTone.Positive, elapsedMs = 900),
                    probe("slowest", "domain_reachability", DiagnosticsTone.Positive, elapsedMs = 60_000),
                ),
            ).marks

        assertEquals(List(3) { ScopeMarkKind.Answered }, marks.map { it.kind })
        assertTrue(marks[0].reach < marks[1].reach)
        assertTrue(marks[1].reach < marks[2].reach)
        assertTrue(marks[2].reach < UnmeasuredReach)
    }

    @Test
    fun `a failure with a measured time is blocked and a timeout has no measured place`() {
        val marks =
            scopeLayout(
                listOf(
                    probe("reset", "dns_integrity", DiagnosticsTone.Negative, elapsedMs = 200),
                    probe("timeout", "dns_integrity", DiagnosticsTone.Negative, 5000, ConnectionStageState.TIMED_OUT),
                    probe("unmeasured", "dns_integrity", DiagnosticsTone.Warning, elapsedMs = null),
                ),
            ).marks

        assertEquals(
            listOf(ScopeMarkKind.Blocked, ScopeMarkKind.Silent, ScopeMarkKind.Silent),
            marks.map { it.kind },
        )
        assertEquals(listOf(UnmeasuredReach, UnmeasuredReach), marks.drop(1).map { it.reach })
        assertTrue(marks[0].reach < UnmeasuredReach)
    }

    @Test
    fun `every probe with a sector has a mark and an answer with no time is in the unmeasured lane`() {
        val marks =
            scopeLayout(
                listOf(
                    probe("untimed", "tcp", DiagnosticsTone.Positive, elapsedMs = null),
                    probe("neutral", "tcp", DiagnosticsTone.Neutral, elapsedMs = null),
                    probe("env", "network_environment", DiagnosticsTone.Positive, elapsedMs = 10),
                ),
            ).marks

        assertEquals(listOf("untimed", "neutral"), marks.map { it.probe.id })
        assertEquals(listOf(ScopeMarkKind.Answered, ScopeMarkKind.Answered), marks.map { it.kind })
        assertEquals(listOf(UnmeasuredReach, UnmeasuredReach), marks.map { it.reach })
    }

    @Test
    fun `a sector with more probes is wider and its marks take even places inside it`() {
        val silent = (1..4).map { probe("dc$it", "service_reachability", DiagnosticsTone.Negative, elapsedMs = null) }
        val layout = scopeLayout(silent)

        // Weights 2, 2, 2, 2 and 6 of 14, over the turn without the scale gap at the top.
        val used = 1f - ScaleGap
        val starts = listOf(0, 2, 4, 6, 8, 14).map { ScaleGap / 2 + used * it / 14 }
        assertEquals(starts.size, layout.sectorStarts.size)
        starts.zip(layout.sectorStarts).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-4f) }
        // The four marks stand at fifths of the last sector.
        val turns = (1..4).map { starts[4] + (starts[5] - starts[4]) * it / 5 }
        assertEquals(listOf("dc1", "dc2", "dc3", "dc4"), layout.marks.map { it.probe.id })
        turns.zip(layout.marks).forEach { (expected, mark) -> assertEquals(expected, mark.turn, 1e-4f) }
    }

    private fun probe(
        id: String,
        probeType: String,
        tone: DiagnosticsTone,
        elapsedMs: Long?,
        state: ConnectionStageState = ConnectionStageState.SUCCEEDED,
    ) = DiagnosticsProbeResultUiModel(
        id = id,
        probeType = probeType,
        target = id,
        outcome = "outcome",
        tone = tone,
        details = persistentListOf(),
        connectionStages =
            DiagnosticsConnectionStageUiModel(
                persistentListOf(
                    DiagnosticsConnectionLaneUiModel(
                        kind = ConnectionLaneKind.TLS13,
                        attempt = null,
                        stages =
                            persistentListOf(
                                DiagnosticsConnectionMeasurementUiModel(
                                    stage = ConnectionStage.TLS,
                                    state = state,
                                    durationMs = null,
                                    elapsedMs = elapsedMs,
                                    byteCount = null,
                                    httpStatusCode = null,
                                ),
                            ),
                        networkScopeUnverified = false,
                    ),
                ),
            ),
    )
}
