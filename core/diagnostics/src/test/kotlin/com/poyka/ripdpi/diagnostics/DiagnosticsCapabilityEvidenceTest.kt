package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCapabilityEvidenceTest {
    @Test
    fun `collectDirectPathCapabilityObservations records QUIC success by authority`() {
        val observations =
            collectDirectPathCapabilityObservations(
                ScanReport(
                    sessionId = "session",
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 1L,
                    finishedAt = 2L,
                    summary = "done",
                    results =
                        listOf(
                            ProbeResult(
                                probeType = "strategy_quic",
                                target = "Strategy · video.example",
                                outcome = "quic_response",
                            ),
                        ),
                ),
            )

        assertEquals(1, observations.size)
        val record = observations.getValue("video.example")
        assertTrue(record.quicUsable == true)
        assertTrue(record.udpUsable == true)
    }
}
