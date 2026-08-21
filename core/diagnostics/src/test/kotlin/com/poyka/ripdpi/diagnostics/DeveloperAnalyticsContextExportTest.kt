package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

internal class DeveloperAnalyticsContextExportTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `developer analytics receives persisted composite stage probe evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            stores.replaceProbeResults(
                "audit-session",
                listOf(
                    ProbeResultEntity(
                        id = "audit-failure",
                        sessionId = "audit-session",
                        probeType = "strategy_https",
                        target = "private.example",
                        outcome = "tls_handshake_failed",
                        detailJson = "[]",
                        createdAt = 31L,
                    ),
                ),
            )
            val outcome = buildSampleCompositeOutcome()
            compositeRunService.putCompletedRun(outcome)
            var capturedContext: DeveloperAnalyticsContext? = null
            val analyticsSource =
                object : DeveloperAnalyticsSource {
                    override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload {
                        capturedContext = context
                        return DeveloperAnalyticsPayload()
                    }
                }

            createArchiveExporter(stores, analyticsSource).createArchive(
                DiagnosticsArchiveRequest(
                    sessionIds = outcome.bundleSessionIds,
                    homeRunId = outcome.runId,
                    reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                    requestedAt = 28L,
                ),
            )

            assertEquals(
                listOf(DeveloperStageProbeEvidence("automatic_audit", "strategy_https", "tls_handshake_failed")),
                capturedContext?.stageProbeEvidence?.filter { it.stageKey == "automatic_audit" },
            )
        }
}
