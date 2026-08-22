package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsOutcomeTaxonomyTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `fixture-driven outcome taxonomy matches classifier`() {
        val fixture =
            json.decodeFromString(
                OutcomeTaxonomyFixture.serializer(),
                repoFixture("diagnostics-contract-fixtures/outcome_taxonomy_current.json").readText(),
            )

        assertEquals(1, fixture.schemaVersion)
        fixture.outcomes.forEach { entry ->
            val classification =
                DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                    probeType = entry.probeType,
                    pathMode = entry.pathMode,
                    outcome = entry.outcome,
                )
            assertEquals(entry.bucket, classification.bucket)
            assertEquals(entry.uiTone, classification.uiTone)
            assertEquals(entry.eventLevel, classification.eventLevel)
            assertEquals(entry.healthyEnoughForSummary, classification.healthyEnoughForSummary)
        }
    }

    @Test
    fun `masque h3 tcp unsupported maps to failed bucket`() {
        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                probeType = "strategy_failure_classification",
                pathMode = ScanPathMode.RAW_PATH,
                outcome = "masque_h3_tcp_unsupported",
            )

        assertEquals(DiagnosticsOutcomeBucket.Failed, classification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Negative, classification.uiTone)
        assertEquals("error", classification.eventLevel)
        assertEquals(false, classification.healthyEnoughForSummary)
    }

    @Test
    fun `tls ech only outcomes map to attention bucket`() {
        val domainClassification =
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                probeType = "domain_reachability",
                pathMode = ScanPathMode.RAW_PATH,
                outcome = "tls_ech_only",
            )
        val strategyClassification =
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                probeType = "strategy_https",
                pathMode = ScanPathMode.RAW_PATH,
                outcome = "tls_ech_only",
            )

        assertEquals(DiagnosticsOutcomeBucket.Attention, domainClassification.bucket)
        assertEquals(DiagnosticsOutcomeBucket.Attention, strategyClassification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Warning, domainClassification.uiTone)
        assertEquals(DiagnosticsOutcomeTone.Warning, strategyClassification.uiTone)
    }

    @Test
    fun `fat header attention is marked as probe artifact`() {
        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                probeType = "tcp_fat_header",
                pathMode = ScanPathMode.RAW_PATH,
                outcome = "tcp_16kb_blocked",
            )

        assertEquals(DiagnosticsOutcomeBucket.Attention, classification.bucket)
        assertEquals(DiagnosticsAttentionKind.ProbeArtifact, classification.attentionKind)
    }

    @Test
    fun `fat header stress result is healthy when domain reachability succeeds`() {
        val reportResults =
            listOf(
                ProbeResult("tcp_fat_header", "8.8.8.8:443 (Google DNS)", "tcp_reset"),
                ProbeResult("domain_reachability", "www.google.com", "tls_ok"),
            )

        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result = reportResults.first(),
                reportResults = reportResults,
            )

        assertEquals(DiagnosticsOutcomeBucket.Healthy, classification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Positive, classification.uiTone)
        assertEquals("info", classification.eventLevel)
    }

    @Test
    fun `fat header stress result stays attention when domain reachability fails`() {
        val reportResults =
            listOf(
                ProbeResult("tcp_fat_header", "8.8.8.8:443 (Google DNS)", "tcp_reset"),
                ProbeResult("domain_reachability", "www.google.com", "unreachable"),
            )

        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result = reportResults.first(),
                reportResults = reportResults,
            )

        assertEquals(DiagnosticsOutcomeBucket.Attention, classification.bucket)
        assertEquals(DiagnosticsAttentionKind.ProbeArtifact, classification.attentionKind)
    }

    @Test
    fun `udp transient dns outcome is inconclusive probe artifact`() {
        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                probeType = "dns_integrity",
                pathMode = ScanPathMode.RAW_PATH,
                outcome = "udp_timeout_transient",
            )

        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, classification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Neutral, classification.uiTone)
        assertEquals(DiagnosticsAttentionKind.ProbeArtifact, classification.attentionKind)
    }

    @Test
    fun `system DNS resolution failure is a failed warning outcome`() {
        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(
                probeType = "dns_integrity",
                pathMode = ScanPathMode.RAW_PATH,
                outcome = "dns_system_resolution_failed",
            )

        assertEquals(DiagnosticsOutcomeBucket.Failed, classification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Negative, classification.uiTone)
        assertEquals("warn", classification.eventLevel)
    }

    @Test
    fun `aggregate collapses repeated udp transient artifacts when dns has healthy evidence`() {
        val bucket =
            DiagnosticsOutcomeTaxonomy.aggregateBucket(
                pathMode = ScanPathMode.RAW_PATH,
                results =
                    listOf(
                        ProbeResult("dns_integrity", "example.org", "dns_match"),
                        ProbeResult("dns_integrity", "example.org", "udp_timeout_transient"),
                        ProbeResult("dns_integrity", "example.net", "udp_plain_dns_unstable"),
                    ),
            )

        assertEquals(DiagnosticsOutcomeBucket.Healthy, bucket)
    }

    @Test
    fun `aggregate keeps dns tampering stronger than udp transient artifacts`() {
        val bucket =
            DiagnosticsOutcomeTaxonomy.aggregateBucket(
                pathMode = ScanPathMode.RAW_PATH,
                results =
                    listOf(
                        ProbeResult("dns_integrity", "blocked.example", "dns_substitution"),
                        ProbeResult("dns_integrity", "blocked.example", "udp_timeout_transient"),
                        ProbeResult("dns_integrity", "example.org", "dns_match"),
                    ),
            )

        assertEquals(DiagnosticsOutcomeBucket.Failed, bucket)
    }

    @Test
    fun `http unreachable after successful tls is downgraded to probe artifact attention`() {
        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result = ProbeResult("strategy_http", "example.com", "http_unreachable"),
                reportResults = listOf(ProbeResult("strategy_https", "example.com", "tls_ok")),
            )

        assertEquals(DiagnosticsOutcomeBucket.Attention, classification.bucket)
        assertEquals(DiagnosticsAttentionKind.ProbeArtifact, classification.attentionKind)
    }

    @Test
    fun `domain tls success with oversized http detail remains healthy probe artifact`() {
        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result =
                    ProbeResult(
                        probeType = "domain_reachability",
                        target = "www.google.com",
                        outcome = "tls_ok",
                        details = listOf(ProbeDetail("httpError", "response_too_large")),
                    ),
                reportResults = emptyList(),
            )

        assertEquals(DiagnosticsOutcomeBucket.Healthy, classification.bucket)
        assertEquals(DiagnosticsAttentionKind.ProbeArtifact, classification.attentionKind)
    }

    @Test
    fun `compatible cdn dns variance with healthy reachability is healthy`() {
        val reportResults =
            listOf(
                compatibleDnsVariance("google.com", comparisonScore = "10"),
                ProbeResult("domain_reachability", "www.google.com", "tls_ok"),
            )

        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result = reportResults.first(),
                reportResults = reportResults,
            )

        assertEquals(DiagnosticsOutcomeBucket.Healthy, classification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Positive, classification.uiTone)
        assertEquals("info", classification.eventLevel)
    }

    @Test
    fun `compatible cdn dns variance with strong evidence remains attention`() {
        val reportResults =
            listOf(
                compatibleDnsVariance(
                    target = "google.com",
                    comparisonScore = "20",
                    extraDetails = listOf(ProbeDetail("recordTypeMismatch", "true")),
                ),
                ProbeResult("domain_reachability", "www.google.com", "tls_ok"),
            )

        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result = reportResults.first(),
                reportResults = reportResults,
            )

        assertEquals(DiagnosticsOutcomeBucket.Attention, classification.bucket)
        assertEquals(DiagnosticsOutcomeTone.Warning, classification.uiTone)
    }

    @Test
    fun `compatible dns variance on non geodns domain remains attention`() {
        val reportResults =
            listOf(
                compatibleDnsVariance("example.org", comparisonScore = "0"),
                ProbeResult("domain_reachability", "example.org", "tls_ok"),
            )

        val classification =
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                pathMode = ScanPathMode.RAW_PATH,
                result = reportResults.first(),
                reportResults = reportResults,
            )

        assertEquals(DiagnosticsOutcomeBucket.Attention, classification.bucket)
    }

    @Test
    fun `approach summaries require all healthy results for validated success`() {
        val sessions = buildApproachSummarySessions()

        val summary =
            DiagnosticsSessionQueries
                .buildApproachSummaries(
                    scanSessions = sessions,
                    usageSessions = emptyList(),
                    json = json,
                ).single { it.approachId.kind == BypassApproachKind.Profile && it.approachId.value == "profile-1" }

        assertEquals(3, summary.validatedScanCount)
        assertEquals(1, summary.validatedSuccessCount)
        assertEquals(1f / 3f, requireNotNull(summary.validatedSuccessRate), 0.0001f)
        assertTrue(summary.topFailureOutcomes.contains("dns_expected_mismatch (1)"))
        assertTrue(summary.topFailureOutcomes.contains("whitelist_sni_failed (1)"))

        val dnsBreakdown = summary.outcomeBreakdown.single { it.probeType == "dns_integrity" }
        assertEquals(1, dnsBreakdown.successCount)
        assertEquals(1, dnsBreakdown.warningCount)
        assertEquals(0, dnsBreakdown.failureCount)
        assertEquals("dns_expected_mismatch", dnsBreakdown.dominantFailureOutcome)

        val tcpBreakdown = summary.outcomeBreakdown.single { it.probeType == "tcp_fat_header" }
        assertEquals(0, tcpBreakdown.successCount)
        assertEquals(0, tcpBreakdown.warningCount)
        assertEquals(1, tcpBreakdown.failureCount)
        assertEquals("whitelist_sni_failed", tcpBreakdown.dominantFailureOutcome)
    }

    private fun buildApproachSummarySessions() =
        listOf(
            diagnosticsSession(
                id = "scan-healthy",
                profileId = "profile-1",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "healthy",
                reportJson =
                    reportJson(
                        sessionId = "scan-healthy",
                        pathMode = ScanPathMode.RAW_PATH,
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "dns_integrity",
                                    target = "example.org",
                                    outcome = "dns_match",
                                ),
                            ),
                    ),
            ),
            diagnosticsSession(
                id = "scan-attention",
                profileId = "profile-1",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "attention",
                reportJson =
                    reportJson(
                        sessionId = "scan-attention",
                        pathMode = ScanPathMode.RAW_PATH,
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "dns_integrity",
                                    target = "example.org",
                                    outcome = "dns_expected_mismatch",
                                ),
                            ),
                    ),
            ),
            diagnosticsSession(
                id = "scan-failed",
                profileId = "profile-1",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "failed",
                reportJson =
                    reportJson(
                        sessionId = "scan-failed",
                        pathMode = ScanPathMode.RAW_PATH,
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "tcp_fat_header",
                                    target = "1.1.1.1:443 (Cloudflare)",
                                    outcome = "whitelist_sni_failed",
                                ),
                            ),
                    ),
            ),
        )

    private fun compatibleDnsVariance(
        target: String,
        comparisonScore: String,
        extraDetails: List<ProbeDetail> = emptyList(),
    ): ProbeResult =
        ProbeResult(
            probeType = "dns_integrity",
            target = target,
            outcome = "dns_compatible_divergence",
            details =
                listOf(
                    ProbeDetail("dnsHttpsClass", "HTTPS_RR_PRESENT"),
                    ProbeDetail("comparisonScore", comparisonScore),
                    ProbeDetail("comparisonSignals", "answer_count_divergent"),
                ) + extraDetails,
        )

    @Test
    fun `persist scan report bridges taxonomy event levels`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = FakeServiceStateStore()
            val report =
                ScanReport(
                    sessionId = "session-1",
                    profileId = "profile-1",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "5 completed · 4 healthy · 1 failed",
                    results =
                        listOf(
                            ProbeResult(
                                probeType = "dns_integrity",
                                target = "example.org",
                                outcome = "dns_match",
                            ),
                            compatibleDnsVariance("google.com", comparisonScore = "10"),
                            ProbeResult(
                                probeType = "domain_reachability",
                                target = "www.google.com",
                                outcome = "tls_ok",
                            ),
                            ProbeResult(
                                probeType = "network_environment",
                                target = "wifi",
                                outcome = "network_available",
                            ),
                            ProbeResult(
                                probeType = "tcp_fat_header",
                                target = "1.1.1.1:443 (Cloudflare)",
                                outcome = "whitelist_sni_failed",
                            ),
                        ),
                ).toEngineScanReportWire()

            DiagnosticsReportPersister.persistScanReport(
                report = report,
                scanRecordStore = stores,
                artifactWriteStore = stores,
                serviceStateStore = serviceStateStore,
                json = json,
            )

            val sessionEvents = stores.nativeEventsState.value.filter { it.sessionId == "session-1" }
            assertEquals(listOf("info", "info", "info", "info", "error"), sessionEvents.map { it.level })
            assertEquals(
                listOf(
                    "dns_integrity",
                    "dns_integrity",
                    "domain_reachability",
                    "network_environment",
                    "tcp_fat_header",
                ),
                sessionEvents.map { it.source },
            )
        }

    @Test
    fun `oversized terminal report retains compact completion metadata`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val report =
                ScanReport(
                    sessionId = "oversized-terminal-report",
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "deadline exceeded",
                    completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                    terminationReason = ScanTerminationReason.DEADLINE_EXCEEDED,
                    results =
                        listOf(
                            ProbeResult(
                                probeType = "http",
                                target = "large.example",
                                outcome = "timeout",
                                details = listOf(ProbeDetail("payload", "x".repeat(1_500_000))),
                            ),
                        ),
                ).toEngineScanReportWire()

            DiagnosticsReportPersister.persistScanReport(
                report = report,
                scanRecordStore = stores,
                artifactWriteStore = stores,
                serviceStateStore = FakeServiceStateStore(),
                json = json,
            )

            val session = requireNotNull(stores.getScanSession(report.sessionId))
            assertNull(session.reportJson)
            assertEquals(ScanCompletionKind.PARTIAL_RESULTS.name, session.reportCompletionKind)
            assertEquals(ScanTerminationReason.DEADLINE_EXCEEDED.name, session.reportTerminationReason)
        }

    private fun reportJson(
        sessionId: String,
        pathMode: ScanPathMode,
        results: List<ProbeResult>,
    ): String =
        json.encodeToString(
            EngineScanReportWire.serializer(),
            ScanReport(
                sessionId = sessionId,
                profileId = "profile-1",
                pathMode = pathMode,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "summary",
                results = results,
            ).toEngineScanReportWire(),
        )
}

@Serializable
private data class OutcomeTaxonomyFixture(
    val schemaVersion: Int,
    val outcomes: List<OutcomeTaxonomyFixtureEntry>,
)

@Serializable
private data class OutcomeTaxonomyFixtureEntry(
    val probeType: String,
    val pathMode: ScanPathMode,
    val outcome: String,
    val bucket: DiagnosticsOutcomeBucket,
    val uiTone: DiagnosticsOutcomeTone,
    val eventLevel: String,
    val healthyEnoughForSummary: Boolean,
)
