package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.DiagnosticsEngineSchemaVersion
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsWireContractTest {
    private val contractJson =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    @Test
    fun `diagnostics schema version matches contract fixture`() {
        val fixture = GoldenContractSupport.readSharedFixture("diagnostics_schema_version.json")
        val contract = Json.decodeFromString<JsonObject>(fixture.trim())
        val rustVersion = (contract["schemaVersion"] as JsonPrimitive).content.toInt()
        assertEquals("Diagnostics schema version mismatch", DiagnosticsEngineSchemaVersion, rustVersion)
    }

    @Test
    fun `kotlin diagnostics progress fields match contract fixture`() {
        val rustFields = readFieldManifest("diagnostics_progress_fields.json")
        val kotlinFields = extractProgressFields()
        assertEquals("EngineProgressWire field mismatch", rustFields.sorted(), kotlinFields.sorted())
    }

    @Test
    fun `kotlin diagnostics report fields are superset of contract fixture`() {
        val rustFields = readFieldManifest("diagnostics_scan_report_fields.json")
        val kotlinFields = extractReportFields()
        val missing = rustFields - kotlinFields
        assertTrue("Kotlin EngineScanReportWire is missing fields: $missing", missing.isEmpty())
    }

    private fun readFieldManifest(filename: String): Set<String> {
        val fixture = GoldenContractSupport.readSharedFixture(filename)
        val array = Json.decodeFromString<JsonArray>(fixture.trim())
        return array.map { (it as JsonPrimitive).content }.toSet()
    }

    private fun extractProgressFields(): Set<String> {
        val progress =
            EngineProgressWire(
                sessionId = "s",
                phase = "p",
                completedSteps = 5,
                totalSteps = 10,
                message = "m",
                latestProbeTarget = "t",
                latestProbeOutcome = "o",
            )
        return extractFieldPaths(contractJson.encodeToJsonElement(progress))
    }

    private fun extractReportFields(): Set<String> {
        val report = buildSampleReportForFieldExtraction()
        return extractFieldPaths(contractJson.encodeToJsonElement(report))
    }

    private fun buildSampleReportForFieldExtraction(): EngineScanReportWire {
        val dnsShortCircuitRationale =
            "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
        return EngineScanReportWire(
            sessionId = "s",
            profileId = "p",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 1000,
            finishedAt = 2000,
            summary = "ok",
            results =
                listOf(
                    EngineProbeResultWire(
                        probeType = "dns",
                        target = "t",
                        outcome = "o",
                        probeRetryCount = 0,
                    ),
                ),
            resolverRecommendation =
                ResolverRecommendation(
                    triggerOutcome = "dns_tampering",
                    selectedResolverId = "cloudflare",
                    selectedProtocol = "doh",
                    selectedEndpoint = "1.1.1.1:443",
                    selectedBootstrapIps = listOf("1.1.1.1"),
                    selectedHost = "cloudflare-dns.com",
                    selectedPort = 443,
                    selectedTlsServerName = "cloudflare-dns.com",
                    selectedDohUrl = "https://cloudflare-dns.com/dns-query",
                    rationale = "DNS tampering detected",
                    persistable = true,
                ),
            strategyProbeReport = buildSampleStrategyProbeReport(dnsShortCircuitRationale),
            engineAnalysisVersion = "1.0",
            classifierVersion = "1.0",
            packVersions = mapOf("core" to 1),
        )
    }

    private fun skippedCandidate(
        id: String,
        label: String,
        family: String,
        totalTargets: Int,
        totalWeight: Int,
    ) = StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        outcome = "skipped",
        rationale = "DNS tampering detected before fallback; strategy escalation skipped",
        succeededTargets = 0,
        totalTargets = totalTargets,
        weightedSuccessScore = 0,
        totalWeight = totalWeight,
        qualityScore = 0,
        skipped = true,
    )

    private fun buildSampleStrategyProbeReport(dnsShortCircuitRationale: String): StrategyProbeReport =
        StrategyProbeReport(
            suiteId = "full_matrix_v1",
            tcpCandidates = listOf(skippedCandidate("baseline_current", "Current strategy", "baseline_current", 6, 18)),
            quicCandidates = listOf(skippedCandidate("quic_disabled", "Current QUIC strategy", "quic_disabled", 2, 4)),
            recommendation =
                StrategyProbeRecommendation(
                    tcpCandidateId = "baseline_current",
                    tcpCandidateLabel = "Current strategy",
                    quicCandidateId = "quic_disabled",
                    quicCandidateLabel = "Current QUIC strategy",
                    rationale = "Resolver override recommended",
                    recommendedProxyConfigJson = "{}",
                ),
            completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            auditAssessment =
                StrategyProbeAuditAssessment(
                    dnsShortCircuited = true,
                    coverage =
                        StrategyProbeAuditCoverage(
                            tcpCandidatesPlanned = 11,
                            tcpCandidatesExecuted = 0,
                            tcpCandidatesSkipped = 1,
                            tcpCandidatesNotApplicable = 0,
                            quicCandidatesPlanned = 2,
                            quicCandidatesExecuted = 0,
                            quicCandidatesSkipped = 1,
                            quicCandidatesNotApplicable = 0,
                            tcpWinnerSucceededTargets = 0,
                            tcpWinnerTotalTargets = 6,
                            quicWinnerSucceededTargets = 0,
                            quicWinnerTotalTargets = 2,
                            matrixCoveragePercent = 0,
                            winnerCoveragePercent = 0,
                        ),
                    confidence =
                        StrategyProbeAuditConfidence(
                            level = StrategyProbeAuditConfidenceLevel.LOW,
                            score = 35,
                            rationale = dnsShortCircuitRationale,
                            warnings = listOf("$dnsShortCircuitRationale."),
                        ),
                ),
            targetSelection =
                StrategyProbeTargetSelection(
                    cohortId = "global-core",
                    cohortLabel = "Global core",
                    domainHosts = listOf("www.youtube.com", "discord.com"),
                    quicHosts = listOf("www.youtube.com"),
                ),
        )

    private fun extractFieldPaths(
        element: JsonElement,
        prefix: String = "",
    ): Set<String> {
        val paths = mutableSetOf<String>()
        if (element !is JsonObject) return paths
        for ((key, child) in element) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            paths.addAll(resolveChildFieldPaths(child, path))
        }
        return paths
    }

    private fun resolveChildFieldPaths(
        child: JsonElement,
        path: String,
    ): Set<String> =
        when (child) {
            is JsonObject -> {
                extractFieldPaths(child, path)
            }

            is JsonArray -> {
                val first = child.firstOrNull()
                if (first is JsonObject) extractFieldPaths(first, "$path[]") else setOf("$path[]")
            }

            else -> {
                setOf(path)
            }
        }
}
