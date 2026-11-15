package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCapabilityEvidenceTest {
    @Test
    fun `scheduled QUIC Initial probe never updates direct capabilities`() {
        val outcomes =
            mapOf(
                "quic_initial_response" to QuicProbeStatus.INITIAL_RESPONSE,
                "quic_retry" to QuicProbeStatus.RETRY,
                "quic_version_negotiation" to QuicProbeStatus.VERSION_NEGOTIATION,
                "quic_initial_close" to QuicProbeStatus.INITIAL_CLOSE,
                "quic_timeout" to QuicProbeStatus.TIMEOUT,
                "quic_setup_error" to QuicProbeStatus.SETUP_ERROR,
                "capability_skipped" to null,
                "quic_error" to QuicProbeStatus.ERROR,
            )
        val host = "video.example"
        ScanPathMode.entries.forEach { path ->
            outcomes.forEach { (outcome, status) ->
                val report =
                    ScanReport(
                        sessionId = "session",
                        profileId = "connectivity",
                        pathMode = path,
                        startedAt = 1L,
                        finishedAt = 2L,
                        summary = "done",
                        results = listOf(ProbeResult("quic_reachability", host, outcome)),
                        observations =
                            listOfNotNull(
                                status?.let {
                                    ObservationFact(ObservationKind.QUIC, host, quic = QuicObservationFact(host, it))
                                },
                            ),
                    )

                assertTrue(
                    "$path: $outcome",
                    buildPersistableDirectPathObservations(report, existingRecords = emptyList()).isEmpty(),
                )
            }
        }
    }

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
