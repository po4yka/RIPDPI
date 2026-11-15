package com.poyka.ripdpi.ui.screens.diagnostics.state

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

    @Test
    fun `neutral controls expose comparison availability only for matching verified transfer windows`() {
        val runs =
            listOf(
                measurement("window_limit").copy(windowComplete = true),
                measurement("window_limit").copy(runIndex = 2, windowComplete = true),
            )
        val evidence = ProbeDetail("transferEvidence", Json.encodeToString(TransferEvidence(runs = runs)))
        val scope =
            listOf(
                ProbeDetail("runs", "2"),
                ProbeDetail("attemptedRuns", "2"),
                ProbeDetail("acceptedRuns", "2"),
                ProbeDetail("remainingRunStopReason", "none"),
                ProbeDetail("bpsReadings", "1000000|1200000"),
                ProbeDetail("bodyGoodputBpsReadings", "3000000|4000000"),
                ProbeDetail("windowBytes", "128"),
                ProbeDetail("url", "https://example.org/fixture"),
                ProbeDetail("addressFamilyReadings", "ipv4|ipv4"),
            )
        val neutral =
            ProbeResult(
                "throughput_window",
                "control",
                "throughput_measured",
                scope + evidence + ProbeDetail("isControl", "true"),
            )
        val testEndpoint = neutral.copy(target = "test", details = scope + evidence + ProbeDetail("isControl", "false"))

        assertTrue(requireNotNull(neutral.toTransferUiModel(listOf(neutral))).comparisonUnavailable)
        assertTrue(
            requireNotNull(
                neutral.toTransferUiModel(listOf(testEndpoint.copy(outcome = "throughput_inconclusive"))),
            ).comparisonUnavailable,
        )
        assertTrue(
            requireNotNull(
                neutral.toTransferUiModel(
                    listOf(
                        testEndpoint.copy(
                            details =
                                testEndpoint.details + ProbeDetail("transferNetworkScope", "unverified"),
                        ),
                    ),
                ),
            ).comparisonUnavailable,
        )
        val unverified = requireNotNull(neutral.toTransferUiModel(listOf(testEndpoint), reportScopeUnverified = true))
        assertTrue(unverified.networkScopeUnverified)
        assertTrue(unverified.comparisonUnavailable)
        val model = requireNotNull(neutral.toTransferUiModel(listOf(testEndpoint)))
        assertFalse(model.comparisonUnavailable)
        assertEquals(listOf(1_000_000L, 1_200_000L), model.windowReadings?.fullRequestRatesBps)
        assertEquals(listOf(3_000_000L, 4_000_000L), model.windowReadings?.bodyGoodputRatesBps)
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
