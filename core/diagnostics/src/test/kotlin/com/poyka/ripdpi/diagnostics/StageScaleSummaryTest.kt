package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageScaleSummaryTest {
    @Test
    fun `text export uses stage evidence without copying targets or arbitrary details`() {
        val result =
            ProbeResult(
                probeType = "selective_availability",
                target = "private.example",
                outcome = "matrix_target_http_error",
                details =
                    listOf(
                        ProbeDetail("dnsStatus", "admitted_addresses"),
                        ProbeDetail("tcpStatus", "ok"),
                        ProbeDetail("tlsStatus", "ok"),
                        ProbeDetail("httpStatus", "failed"),
                        ProbeDetail("httpStatusCode", "403"),
                        ProbeDetail("bodyStatus", "not_run"),
                        ProbeDetail("privateNote", "secret-data"),
                    ),
            )
        val lines = summary(listOf(result)).filter { it.startsWith("connectionStage[") }
        assertTrue(lines.any { it.contains("stage=HTTP_HEADERS state=OBSERVED") && it.contains("httpStatusCode=403") })
        assertTrue(lines.any { it.contains("stage=DNS state=NOT_APPLICABLE") })
        assertFalse(lines.any { it.contains("private.example") || it.contains("secret-data") })
    }

    @Test
    fun `legacy unknown stages do not create fabricated export measurements`() {
        val result = ProbeResult("dns", "private.example", "substituted", listOf(ProbeDetail("attempts", "private")))
        assertTrue(connectionStageSummaryLines(listOf(result)).isEmpty())
    }

    @Test
    fun `stage export is bounded and carries global network uncertainty`() {
        val result = ProbeResult("strategy_http", "private.example", "http_ok", listOf(ProbeDetail("status", "200")))
        val lines = connectionStageSummaryLines(List(40) { result }, networkScopeUnverified = true)
        assertTrue(lines.size <= 257)
        assertTrue(lines.last() == "connectionStageTruncated=true")
        assertTrue(lines.filter { it.startsWith("connectionStage[") }.all { it.contains("networkScope=unverified") })
    }

    @Test
    fun `archive stage projection is idempotent and never exports original identifiers`() {
        val report =
            EngineScanReportWire(
                sessionId = "private-session",
                profileId = "test",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "private-summary",
                results =
                    listOf(
                        ProbeResult(
                            "strategy_http",
                            "private.example",
                            "http_ok",
                            listOf(ProbeDetail("status", "200"), ProbeDetail("resolved", "192.0.2.8")),
                        ).toEngineProbeResultWire(),
                    ),
            )
        val document = DiagnosticsSummaryDocument().withConnectionStageEvidence(report)
        assertEquals(document, document.withConnectionStageEvidence(report))
        val text = document.reportMetadata.lines.joinToString("\n")
        assertTrue(text.contains("HTTP_HEADERS"))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("192.0.2.8"))
    }

    private fun summary(results: List<ProbeResult>): List<String> =
        DiagnosticsSummaryProjector()
            .project(
                session = null,
                report = DiagnosticsSessionProjection(results = results),
                latestSnapshotModel = null,
                latestContextModel = null,
                latestTelemetry = null,
                selectedResults = emptyList(),
                warnings = emptyList(),
            ).reportMetadata.lines
}
