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
    fun `top level completion determines standalone display summary`() {
        val terminationSummaries =
            mapOf(
                ScanTerminationReason.NETWORK_UNAVAILABLE to ScanUnavailableOfflineSummary,
                ScanTerminationReason.USER_CANCELLED to ScanCancelledByUserSummary,
                ScanTerminationReason.DEADLINE_EXCEEDED to ScanDeadlineExceededSummary,
                ScanTerminationReason.ENGINE_ERROR to ScanEngineErrorSummary,
                ScanTerminationReason.WORKER_PANICKED to ScanWorkerPanickedSummary,
            )

        terminationSummaries.forEach { (reason, expected) ->
            assertEquals(
                expected,
                standaloneCompletionReport(
                    completionKind = ScanCompletionKind.TERMINATED,
                    terminationReason = reason,
                ).displaySummary(),
            )
        }
        assertEquals(
            ScanCompletedWithPartialResultsSummary,
            standaloneCompletionReport(ScanCompletionKind.PARTIAL_RESULTS).displaySummary(),
        )
    }

    @Test
    fun `partial completion preserves availability and termination reason`() {
        val terminationSummaries =
            mapOf(
                ScanTerminationReason.USER_CANCELLED to ScanCancelledByUserSummary,
                ScanTerminationReason.DEADLINE_EXCEEDED to ScanDeadlineExceededSummary,
                ScanTerminationReason.WORKER_PANICKED to ScanWorkerPanickedSummary,
            )

        terminationSummaries.forEach { (reason, reasonSummary) ->
            assertEquals(
                ScanCompletedWithPartialResultsSummary + ScanPartialResultsReasonSeparator + reasonSummary,
                standaloneCompletionReport(
                    completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                    terminationReason = reason,
                ).displaySummary(),
            )
        }
    }

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
    fun `completed lifecycle with network termination is unavailable`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val sessionId = "session-offline"
            val report =
                ScanReport(
                    sessionId = sessionId,
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "0 completed",
                    completionKind = ScanCompletionKind.TERMINATED,
                    terminationReason = ScanTerminationReason.NETWORK_UNAVAILABLE,
                    diagnoses =
                        listOf(
                            Diagnosis(
                                code = "network_unavailable",
                                summary = "No network",
                                severity = "error",
                            ),
                        ),
                )
            stores.upsertScanSession(
                ScanSessionEntity(
                    id = sessionId,
                    profileId = report.profileId,
                    pathMode = report.pathMode.name,
                    serviceMode = "VPN",
                    status = "completed",
                    summary = report.summary,
                    reportJson =
                        json.encodeToString(
                            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                                .serializer(),
                            report.toEngineScanReportWire(),
                        ),
                    startedAt = report.startedAt,
                    finishedAt = report.finishedAt,
                ),
            )

            val summary =
                buildCompletedStageSummary(
                    spec = HomeCompositeStageSpecs.first(),
                    sessionId = sessionId,
                    session =
                        DiagnosticScanSession(
                            id = sessionId,
                            profileId = report.profileId,
                            pathMode = report.pathMode.name,
                            serviceMode = "VPN",
                            status = "completed",
                            summary = report.summary,
                            startedAt = report.startedAt,
                            finishedAt = report.finishedAt,
                        ),
                    scanRecordStore = stores,
                    json = json,
                )

            assertEquals(DiagnosticsHomeCompositeStageStatus.UNAVAILABLE, summary.status)
            assertEquals("Automatic audit unavailable", summary.headline)
        }

    @Test
    fun `oversized terminal reports keep their completion state for Home`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val sessionId = "oversized-terminal-session"
            stores.upsertScanSession(
                ScanSessionEntity(
                    id = sessionId,
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH.name,
                    serviceMode = "VPN",
                    status = "completed",
                    summary = "report stored separately",
                    reportJson = null,
                    reportCompletionKind = ScanCompletionKind.TERMINATED.name,
                    reportTerminationReason = ScanTerminationReason.NETWORK_UNAVAILABLE.name,
                    startedAt = 10L,
                    finishedAt = 20L,
                ),
            )

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
                            summary = "report stored separately",
                            startedAt = 10L,
                            finishedAt = 20L,
                        ),
                    scanRecordStore = stores,
                    json = json,
                )

            assertEquals(DiagnosticsHomeCompositeStageStatus.UNAVAILABLE, summary.status)
        }

    @Test
    fun `completed sessions with partial reports are classified as failed by Home`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val sessionId = "oversized-partial-session"
            stores.upsertScanSession(
                ScanSessionEntity(
                    id = sessionId,
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH.name,
                    serviceMode = "VPN",
                    status = "completed",
                    summary = "report stored separately",
                    reportJson = null,
                    reportCompletionKind = ScanCompletionKind.PARTIAL_RESULTS.name,
                    reportTerminationReason = ScanTerminationReason.DEADLINE_EXCEEDED.name,
                    startedAt = 10L,
                    finishedAt = 20L,
                ),
            )

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
                            summary = "report stored separately",
                            startedAt = 10L,
                            finishedAt = 20L,
                        ),
                    scanRecordStore = stores,
                    json = json,
                )

            assertEquals(DiagnosticsHomeCompositeStageStatus.FAILED, summary.status)
        }

    @Test
    fun `partial report remains unverified for approach validation`() {
        val report =
            ScanReport(
                sessionId = "session-history",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = ScanCancelledSummary,
                completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                terminationReason = ScanTerminationReason.DEADLINE_EXCEEDED,
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

        assertEquals(
            listOf("unverified", 0, 0, null, null),
            listOf(
                approach.verificationState,
                approach.validatedScanCount,
                approach.validatedSuccessCount,
                approach.validatedSuccessRate,
                approach.lastValidatedResult,
            ),
        )
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
    fun `summary projector surfaces transparent direct mode verdict`() {
        val summary =
            ScanReport(
                sessionId = "session-transparent",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                directModeVerdict =
                    DirectModeVerdict(
                        result = DirectModeVerdictResult.TRANSPARENT_WORKS,
                        transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                        authority = "example.org",
                    ),
            ).displaySummary()

        assertEquals("Direct mode works transparently", summary)
    }

    @Test
    fun `summary projector surfaces healthy direct path with synthetic attention`() {
        val summary =
            ScanReport(
                sessionId = "session-clean-synthetic",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                results =
                    listOf(
                        ProbeResult("domain_reachability", "youtube.com", "tls_ok"),
                        ProbeResult("domain_reachability", "discord.com", "tls_ok"),
                        ProbeResult("domain_reachability", "proton.me", "tls_ok"),
                        ProbeResult("tcp_fat_header", "172.67.70.222:443 (Cloudflare)", "tcp_16kb_blocked"),
                    ),
            ).displaySummary()

        assertEquals("Direct connectivity is healthy; only synthetic probe artifacts need attention", summary)
    }

    @Test
    fun `summary projector qualifies suspected ip filtering cause`() {
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

        assertTrue(summary.contains("observed", ignoreCase = true))
        assertTrue(summary.contains("candidate", ignoreCase = true))
        assertTrue(summary.contains("not established", ignoreCase = true))
        assertTrue(!summary.contains("likely IP block", ignoreCase = true))
    }

    @Test
    fun `summary projector reports confirmed post client hello failure without claiming blocking`() {
        val summary =
            ScanReport(
                sessionId = "session-no-direct-tls",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                directModeVerdict =
                    DirectModeVerdict(
                        result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                        reasonCode = DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE,
                        transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                        authority = "example.org",
                    ),
            ).displaySummary()

        assertEquals("No direct solution: TLS handshake failed after ClientHello", summary)
    }

    @Test
    fun `summary projector surfaces no tcp fallback no direct solution reason`() {
        val summary =
            ScanReport(
                sessionId = "session-no-direct-quic",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Scan completed",
                directModeVerdict =
                    DirectModeVerdict(
                        result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                        reasonCode = DirectModeReasonCode.NO_TCP_FALLBACK,
                        transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                        authority = "example.org",
                    ),
            ).displaySummary()

        assertEquals("No direct solution: app did not fall back from QUIC", summary)
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

    private fun standaloneCompletionReport(
        completionKind: ScanCompletionKind,
        terminationReason: ScanTerminationReason? = null,
    ) = ScanReport(
        sessionId = "session-completion",
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "Lifecycle completed",
        completionKind = completionKind,
        terminationReason = terminationReason,
    )
}
