package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsDisplaySummaryTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `summary projector uses dns fallback summary for archive rendering`() {
        val session =
            ScanSessionEntity(
                id = "session-archive",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH.name,
                serviceMode = "VPN",
                status = "completed",
                summary = ScanCancelledSummary,
                reportJson = null,
                startedAt = 10L,
                finishedAt = 20L,
            )
        val report =
            ScanReport(
                sessionId = session.id,
                profileId = session.profileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = ScanCancelledSummary,
                results =
                    listOf(
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "blocked.example",
                            outcome = "dns_substitution",
                        ),
                    ),
                strategyProbeReport =
                    strategyProbeReport(
                        StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK,
                    ),
            ).toEngineScanReportWire().toSessionProjection()

        val document =
            DiagnosticsSummaryProjector().project(
                session = session,
                report = report,
                latestSnapshotModel = null,
                latestContextModel = null,
                latestTelemetry = null,
                selectedResults = emptyList(),
                warnings = emptyList(),
            )

        assertTrue(document.header.lines.contains("summary=Scan completed with DNS fallback"))
    }

    @Test
    fun `completed stage summary uses derived dns fallback summary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val sessionId = "session-stage"
            val persistedSession =
                ScanSessionEntity(
                    id = sessionId,
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH.name,
                    serviceMode = "VPN",
                    status = "completed",
                    summary = ScanCancelledSummary,
                    reportJson =
                        json.encodeToString(
                            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                .serializer(),
                            ScanReport(
                                sessionId = sessionId,
                                profileId = "automatic-audit",
                                pathMode = ScanPathMode.RAW_PATH,
                                startedAt = 10L,
                                finishedAt = 20L,
                                summary = ScanCancelledSummary,
                                results =
                                    listOf(
                                        ProbeResult(
                                            probeType = "dns_integrity",
                                            target = "blocked.example",
                                            outcome = "dns_substitution",
                                        ),
                                    ),
                                strategyProbeReport =
                                    strategyProbeReport(
                                        StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK,
                                    ),
                            ).toEngineScanReportWire(),
                        ),
                    startedAt = 10L,
                    finishedAt = 20L,
                )
            stores.upsertScanSession(persistedSession)

            val summary =
                buildCompletedStageSummary(
                    spec = HomeCompositeStageSpecs.first(),
                    sessionId = sessionId,
                    session =
                        DiagnosticScanSession(
                            id = sessionId,
                            profileId = "automatic-audit",
                            pathMode = ScanPathMode.RAW_PATH.name,
                            serviceMode = "VPN",
                            status = "completed",
                            summary = ScanCancelledSummary,
                            startedAt = 10L,
                            finishedAt = 20L,
                        ),
                    scanRecordStore = stores,
                    json = json,
                )

            assertEquals("Scan completed with DNS fallback", summary.summary)
        }

    @Test
    fun `approach summary uses partial result wording instead of raw cancelled summary`() {
        val report =
            ScanReport(
                sessionId = "session-history",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = ScanCancelledSummary,
                results = listOf(ProbeResult(probeType = "http", target = "example.org", outcome = "http_ok")),
            )
        val session =
            ScanSessionEntity(
                id = report.sessionId,
                profileId = report.profileId,
                approachProfileId = "automatic-probing",
                approachProfileName = "Automatic probing",
                strategyId = "strategy-1",
                strategyLabel = "Strategy 1",
                pathMode = ScanPathMode.RAW_PATH.name,
                serviceMode = "VPN",
                status = "completed",
                summary = ScanCancelledSummary,
                reportJson =
                    json.encodeToString(
                        com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                            .serializer(),
                        report.toEngineScanReportWire(),
                    ),
                startedAt = 10L,
                finishedAt = 20L,
            )

        val summaries =
            DiagnosticsSessionQueries.buildApproachSummaries(
                scanSessions = listOf(session),
                usageSessions = emptyList(),
                json = json,
            )
        val approach = summaries.first { it.approachId.value == "strategy-1" }

        assertEquals(ScanCompletedWithPartialResultsSummary, approach.lastValidatedResult)
    }

    @Test
    fun `summary projector surfaces owned stack only direct verdict`() {
        val summary =
            ScanReport(
                sessionId = "session-owned-stack",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                directModeVerdict =
                    DirectModeVerdict(
                        result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                        reasonCode = DirectModeReasonCode.OWNED_STACK_REQUIRED,
                        transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                        authority = "example.org",
                    ),
            ).displaySummary()

        assertEquals("Direct mode works only in RIPDPI owned stack", summary)
    }

    @Test
    fun `summary projector surfaces no direct solution verdict`() {
        val summary =
            ScanReport(
                sessionId = "session-no-direct",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                directModeVerdict =
                    DirectModeVerdict(
                        result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                        reasonCode = DirectModeReasonCode.IP_BLOCKED,
                        transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                        authority = "example.org",
                    ),
            ).displaySummary()

        assertEquals("No direct solution for this authority", summary)
    }

    private fun strategyProbeReport(completionKind: StrategyProbeCompletionKind): StrategyProbeReport =
        StrategyProbeReport(
            suiteId = "quick_v1",
            tcpCandidates = emptyList(),
            quicCandidates = emptyList(),
            recommendation =
                StrategyProbeRecommendation(
                    tcpCandidateId = "tcp-1",
                    tcpCandidateLabel = "TCP",
                    quicCandidateId = "quic-1",
                    quicCandidateLabel = "QUIC",
                    rationale = "best path",
                    recommendedProxyConfigJson = "{}",
                ),
            completionKind = completionKind,
        )
}
