package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.buildDeveloperStageTimingEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsArchiveStageTimingEvidenceTest {
    @Test
    fun `stage timing evidence exports cpu and measured protocol phase totals`() {
        val stage =
            DiagnosticsArchiveCompositeStageSelection(
                stageSummary =
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "default_connectivity",
                        stageLabel = "Default connectivity",
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = "session-1",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Complete",
                        summary = "Complete",
                        wallClockMs = 900,
                        cpuMs = 240,
                    ),
                session = null,
                report =
                    EngineScanReportWire(
                        sessionId = "session-1",
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 100,
                        finishedAt = 1_000,
                        summary = "Complete",
                        results =
                            listOf(
                                EngineProbeResultWire(
                                    probeType = "dns_integrity",
                                    target = "example.com",
                                    outcome = "dns_consistent",
                                    details =
                                        listOf(
                                            ProbeDetail("udpLatencyMs", "12"),
                                            ProbeDetail("encryptedLatencyMs", "28"),
                                        ),
                                ),
                                EngineProbeResultWire(
                                    probeType = "strategy_https",
                                    target = "example.com",
                                    outcome = "tls_ok",
                                    details =
                                        listOf(
                                            ProbeDetail("tcpConnectMs", "31"),
                                            ProbeDetail("tlsHandshakeMs", "47"),
                                        ),
                                ),
                                EngineProbeResultWire(
                                    probeType = "strategy_http",
                                    target = "example.com",
                                    outcome = "http_ok",
                                    details = listOf(ProbeDetail("ttfbMs", "19")),
                                ),
                            ),
                    ),
                results = emptyList(),
                snapshots = emptyList(),
                contexts = emptyList(),
                events = emptyList(),
            )

        val evidence = buildDeveloperStageTimingEvidence(stage)

        assertEquals("default_connectivity", evidence.stageKey)
        assertEquals(900L, evidence.wallClockMs)
        assertEquals(240L, evidence.cpuMs)
        assertEquals(40L, evidence.dnsMs)
        assertEquals(31L, evidence.tcpHandshakeMs)
        assertEquals(47L, evidence.tlsHandshakeMs)
        assertEquals(19L, evidence.ttfbMs)
    }
}
