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
    fun `a slower answer lands farther from the hub and stays inside the rim`() {
        val marks =
            scopeMarks(
                listOf(
                    probe("fast", "domain_reachability", DiagnosticsTone.Positive, elapsedMs = 40),
                    probe("slow", "domain_reachability", DiagnosticsTone.Positive, elapsedMs = 900),
                ),
            )

        assertEquals(listOf(ScopeMarkKind.Answered, ScopeMarkKind.Answered), marks.map { it.kind })
        assertTrue(marks[0].reach < marks[1].reach)
        assertTrue(marks[1].reach < 1f)
        // Both marks stay inside the third sector of five.
        assertTrue(marks.all { it.turn > 2f / 5 && it.turn < 3f / 5 })
        assertTrue(marks[0].turn < marks[1].turn)
    }

    @Test
    fun `a failure with a measured time is blocked and a timeout sits on the rim`() {
        val marks =
            scopeMarks(
                listOf(
                    probe("reset", "dns_integrity", DiagnosticsTone.Negative, elapsedMs = 200),
                    probe("timeout", "dns_integrity", DiagnosticsTone.Negative, 5000, ConnectionStageState.TIMED_OUT),
                    probe("unmeasured", "dns_integrity", DiagnosticsTone.Warning, elapsedMs = null),
                ),
            )

        assertEquals(
            listOf(ScopeMarkKind.Blocked, ScopeMarkKind.Silent, ScopeMarkKind.Silent),
            marks.map { it.kind },
        )
        assertEquals(listOf(1f, 1f), marks.drop(1).map { it.reach })
        assertTrue(marks[0].reach < 1f)
    }

    @Test
    fun `many silent probes of one sector become one stacked mark`() {
        val silent = (1..4).map { probe("dc$it", "service_reachability", DiagnosticsTone.Negative, elapsedMs = null) }
        val answered = probe("ok", "service_reachability", DiagnosticsTone.Positive, elapsedMs = 120)

        val marks = scopeMarks(silent + answered)

        assertEquals(listOf("ok", "dc1"), marks.map { it.probe.id })
        assertEquals(listOf(false, true), marks.map { it.stacked })
    }

    @Test
    fun `a probe with no sector or no measured answer has no mark`() {
        val marks =
            scopeMarks(
                listOf(
                    probe("env", "network_environment", DiagnosticsTone.Positive, elapsedMs = 10),
                    probe("untimed", "tcp", DiagnosticsTone.Positive, elapsedMs = null),
                ),
            )

        assertTrue(marks.isEmpty())
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
