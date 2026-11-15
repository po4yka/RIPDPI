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
    fun `strategy QUIC records and observations never update direct capabilities`() {
        val outcomes =
            mapOf(
                "quic_initial_response" to StrategyProbeStatus.SUCCESS,
                "quic_retry" to StrategyProbeStatus.PARTIAL,
                "quic_version_negotiation" to StrategyProbeStatus.PARTIAL,
                "quic_response" to StrategyProbeStatus.PARTIAL,
                "quic_error" to StrategyProbeStatus.FAILED,
            )
        outcomes.forEach { (outcome, status) ->
            val probe =
                ProbeResult(
                    probeType = "strategy_quic",
                    target = "Strategy · video.example",
                    outcome = outcome,
                    details = listOf(ProbeDetail("connectedIp", "192.0.2.1")),
                )
            val report =
                ScanReport(
                    sessionId = "session",
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 1L,
                    finishedAt = 2L,
                    summary = "done",
                    results = listOf(probe),
                    observations =
                        listOf(
                            ObservationFact(
                                kind = ObservationKind.STRATEGY,
                                target = probe.target,
                                strategy =
                                    StrategyObservationFact(
                                        protocol = StrategyProbeProtocol.QUIC,
                                        status = status,
                                    ),
                            ),
                        ),
                )

            assertTrue(outcome, buildPersistableDirectPathObservations(report, emptyList()).isEmpty())
            assertEquals(outcome, outcome == "quic_initial_response", probe.edgeTransportKind() != null)
        }
    }
}
