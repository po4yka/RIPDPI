package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
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
    fun `approach summaries require all healthy results for validated success`() {
        val sessions =
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
                    summary = "3 completed · 2 healthy · 1 failed",
                    results =
                        listOf(
                            ProbeResult(
                                probeType = "dns_integrity",
                                target = "example.org",
                                outcome = "dns_match",
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
            assertEquals(listOf("info", "info", "error"), sessionEvents.map { it.level })
            assertEquals(
                listOf("dns_integrity", "network_environment", "tcp_fat_header"),
                sessionEvents.map { it.source },
            )
        }

    private fun reportJson(
        sessionId: String,
        pathMode: ScanPathMode,
        results: List<ProbeResult>,
    ): String =
        json.encodeToString(
            ScanReport.serializer(),
            ScanReport(
                sessionId = sessionId,
                profileId = "profile-1",
                pathMode = pathMode,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "summary",
                results = results,
            ),
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
