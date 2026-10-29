package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.navigation.Route
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsReplayActionTest {
    private fun probe(
        target: String,
        tone: DiagnosticsTone = DiagnosticsTone.Negative,
    ) = DiagnosticsProbeResultUiModel(
        id = "p1",
        probeType = "tls",
        target = target,
        outcome = "reset",
        tone = tone,
        details = persistentListOf(),
    )

    @Test
    fun `failed probe with host and candidate id routes to replay failure`() {
        assertEquals(
            Route.ReplayFailure(domain = "www.youtube.com", strategyId = "split"),
            replayRouteFor("split", probe("www.youtube.com")),
        )
    }

    @Test
    fun `no replay route without a failed probe, a hostname target, or an activatable candidate id`() {
        assertNull(replayRouteFor("split", probe("www.youtube.com", DiagnosticsTone.Positive)))
        assertNull(replayRouteFor("split", probe("")))
        assertNull(replayRouteFor("split", probe("https://a.example/x")))
        assertNull(replayRouteFor(" ", probe("www.youtube.com")))
        assertNull(replayRouteFor("tlsrec_split_host", probe("www.youtube.com")))
    }
}
