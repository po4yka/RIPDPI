package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.TransferEvidence
import com.poyka.ripdpi.diagnostics.TransferMeasurement
import com.poyka.ripdpi.diagnostics.TransferProgress
import com.poyka.ripdpi.diagnostics.TransferProgressSample
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTransferUiTest {
    @Test
    fun `legacy or malformed evidence has no fabricated transfer card`() {
        assertNull(probe().toTransferUiModel())
        assertNull(probe(ProbeDetail("transferEvidence", "invalid")).toTransferUiModel())
    }

    @Test
    fun `live transfer preserves unknown length and unobserved times`() {
        val ui = TransferProgress("example.org", TransferMeasurement(1, 2, 0, elapsedMs = 20)).toTransferUiModel()
        assertEquals("example.org", ui.target)
        assertNull(ui.runs.single().expectedBodyByteCount)
        assertNull(ui.runs.single().firstBodyByteMs)
        assertNull(ui.runs.single().lastBodyProgressMs)
        assertNull(ui.runs.single().terminationReason)
    }

    @Test
    fun `network change retains measured stop cause and partial body`() {
        val run = measurement("early_eof")
        val ui =
            probe(
                ProbeDetail("transferEvidence", Json.encodeToString(TransferEvidence(runs = listOf(run)))),
                ProbeDetail("transferNetworkScope", "unverified"),
            ).toTransferUiModel()!!
        assertTrue(ui.networkScopeUnverified)
        assertEquals("early_eof", ui.runs.single().terminationReason)
        assertEquals(128L, ui.runs.single().receivedBodyByteCount)
        assertEquals(10L, ui.runs.single().firstBodyByteMs)
        assertEquals(20L, ui.runs.single().lastBodyProgressMs)
        assertFalse(ui.runs.single().responseComplete)
    }

    @Test
    fun `window completion remains separate from response completion across repeated runs`() {
        val runs =
            listOf(
                measurement("window_limit").copy(windowComplete = true),
                measurement("content_length_complete").copy(
                    runIndex = 2,
                    receivedBodyByteCount = 256,
                    firstBodyByteMs = 20,
                    responseComplete = true,
                    samples = listOf(TransferProgressSample(20, 256)),
                ),
            )
        val ui =
            probe(
                ProbeDetail("transferEvidence", Json.encodeToString(TransferEvidence(runs = runs))),
            ).toTransferUiModel()!!
        assertEquals(2, ui.runs.size)
        assertTrue(ui.runs.first().windowComplete)
        assertFalse(ui.runs.first().responseComplete)
        assertTrue(ui.runs.last().responseComplete)
        assertEquals(
            256L,
            ui.runs
                .last()
                .samples
                .single()
                .bodyByteCount,
        )
    }

    private fun probe(vararg details: ProbeDetail) =
        ProbeResult("throughput", "example.org", "measured", details.toList())

    private fun measurement(reason: String) =
        TransferMeasurement(
            runIndex = 1,
            runCount = 2,
            receivedBodyByteCount = 128,
            expectedBodyByteCount = 256,
            elapsedMs = 30,
            firstBodyByteMs = 10,
            lastBodyProgressMs = 20,
            terminationReason = reason,
            samples = listOf(TransferProgressSample(10, 64), TransferProgressSample(20, 128)),
        )
}
