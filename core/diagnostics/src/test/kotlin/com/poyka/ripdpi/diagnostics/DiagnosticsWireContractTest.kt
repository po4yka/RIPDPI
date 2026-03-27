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
        val report =
            EngineScanReportWire(
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
                strategyProbeReport =
                    StrategyProbeReport(
                        suiteId = "full_matrix_v1",
                        tcpCandidates =
                            listOf(
                                StrategyProbeCandidateSummary(
                                    id = "baseline_current",
                                    label = "Current strategy",
                                    family = "baseline_current",
                                    outcome = "skipped",
                                    rationale =
                                        "DNS tampering detected before fallback; TCP strategy escalation skipped",
                                    succeededTargets = 0,
                                    totalTargets = 6,
                                    weightedSuccessScore = 0,
                                    totalWeight = 18,
                                    qualityScore = 0,
                                    skipped = true,
                                ),
                            ),
                        quicCandidates =
                            listOf(
                                StrategyProbeCandidateSummary(
                                    id = "quic_disabled",
                                    label = "Current QUIC strategy",
                                    family = "quic_disabled",
                                    outcome = "skipped",
                                    rationale =
                                        "DNS tampering detected before fallback; QUIC strategy escalation skipped",
                                    succeededTargets = 0,
                                    totalTargets = 2,
                                    weightedSuccessScore = 0,
                                    totalWeight = 4,
                                    qualityScore = 0,
                                    skipped = true,
                                ),
                            ),
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
                                        rationale =
                                            "Baseline DNS tampering short-circuited the audit before fallback candidates ran",
                                        warnings =
                                            listOf(
                                                "Baseline DNS tampering short-circuited the audit before fallback candidates ran.",
                                            ),
                                    ),
                            ),
                        targetSelection =
                            StrategyProbeTargetSelection(
                                cohortId = "global-core",
                                cohortLabel = "Global core",
                                domainHosts = listOf("www.youtube.com", "discord.com"),
                                quicHosts = listOf("www.youtube.com"),
                            ),
                    ),
                engineAnalysisVersion = "1.0",
                classifierVersion = "1.0",
                packVersions = mapOf("core" to 1),
            )
        return extractFieldPaths(contractJson.encodeToJsonElement(report))
    }

    private fun extractFieldPaths(
        element: JsonElement,
        prefix: String = "",
    ): Set<String> {
        val paths = mutableSetOf<String>()
        when (element) {
            is JsonObject -> {
                for ((key, child) in element) {
                    val path = if (prefix.isEmpty()) key else "$prefix.$key"
                    when (child) {
                        is JsonObject -> {
                            paths.addAll(extractFieldPaths(child, path))
                        }

                        is JsonArray -> {
                            val first = child.firstOrNull()
                            if (first is JsonObject) {
                                paths.addAll(extractFieldPaths(first, "$path[]"))
                            } else {
                                paths.add("$path[]")
                            }
                        }

                        else -> {
                            paths.add(path)
                        }
                    }
                }
            }

            else -> {}
        }
        return paths
    }
}
