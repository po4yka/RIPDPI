package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPostScanHealthSupportTest {
    @Test
    fun `completed report with payload is post scan healthy`() {
        val health =
            ScanReport(
                sessionId = "session",
                profileId = "profile",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                results = listOf(ProbeResult("domain_reachability", "youtube.com", "tls_ok")),
                engineAnalysisVersion = "kotlin-enriched",
            ).postScanHealthSummary()

        assertEquals(DiagnosticsPostScanHealthState.HEALTHY, health.healthState)
        assertTrue(health.scanFinalized)
        assertTrue(health.resultPayloadPresent)
        assertTrue(health.nativeReportVersionPresent)
    }

    @Test
    fun `cancelled report without payload is inconclusive`() {
        val health =
            ScanReport(
                sessionId = "session",
                profileId = "profile",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = ScanCancelledSummary,
            ).postScanHealthSummary()

        assertEquals(DiagnosticsPostScanHealthState.INCONCLUSIVE, health.healthState)
    }
}
