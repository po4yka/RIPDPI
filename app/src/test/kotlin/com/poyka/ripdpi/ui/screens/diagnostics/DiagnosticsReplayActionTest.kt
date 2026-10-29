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
            replayRouteFor("split", null, probe("www.youtube.com")),
        )
    }

    @Test
    fun `native candidate with a proxy config routes with that config`() {
        assertEquals(
            Route.ReplayFailure("www.youtube.com", "tlsrec_split_host", "{\"k\":1}"),
            replayRouteFor("tlsrec_split_host", "{\"k\":1}", probe("www.youtube.com")),
        )
    }

    @Test
    fun `no replay route without a failed probe, a hostname target, or an activatable candidate id`() {
        assertNull(replayRouteFor("split", null, probe("www.youtube.com", DiagnosticsTone.Positive)))
        assertNull(replayRouteFor("split", null, probe("")))
        assertNull(replayRouteFor("split", null, probe("https://a.example/x")))
        assertNull(replayRouteFor(" ", null, probe("www.youtube.com")))
        assertNull(replayRouteFor("tlsrec_split_host", null, probe("www.youtube.com")))
    }
}
