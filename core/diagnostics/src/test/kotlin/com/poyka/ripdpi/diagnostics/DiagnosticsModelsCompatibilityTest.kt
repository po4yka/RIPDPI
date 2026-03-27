package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsModelsCompatibilityTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

    @Test
    fun `legacy scan request decodes with new defaults`() {
        val request =
            json.decodeFromString(
                ScanRequest.serializer(),
                """
                {
                  "profileId": "default",
                  "displayName": "Default diagnostics",
                  "pathMode": "RAW_PATH",
                  "domainTargets": [],
                  "dnsTargets": [],
                  "tcpTargets": [],
                  "whitelistSni": []
                }
                """.trimIndent(),
            )

        assertEquals(DiagnosticProfileFamily.GENERAL, request.family)
        assertNull(request.regionTag)
        assertFalse(request.manualOnly)
        assertTrue(request.packRefs.isEmpty())
        assertTrue(request.serviceTargets.isEmpty())
        assertTrue(request.circumventionTargets.isEmpty())
        assertTrue(request.throughputTargets.isEmpty())
    }

    @Test
    fun `legacy scan report decodes with diagnosis defaults`() {
        val report =
            json.decodeFromString(
                ScanReport.serializer(),
                """
                {
                  "sessionId": "session-1",
                  "profileId": "default",
                  "pathMode": "RAW_PATH",
                  "startedAt": 1,
                  "finishedAt": 2,
                  "summary": "done",
                  "results": []
                }
                """.trimIndent(),
            )

        assertTrue(report.diagnoses.isEmpty())
        assertNull(report.classifierVersion)
        assertTrue(report.packVersions.isEmpty())
    }

    @Test
    fun `legacy strategy probe report decodes without audit assessment`() {
        val report =
            json.decodeFromString(
                ScanReport.serializer(),
                """
                {
                  "sessionId": "session-1",
                  "profileId": "automatic-audit",
                  "pathMode": "RAW_PATH",
                  "startedAt": 1,
                  "finishedAt": 2,
                  "summary": "audit complete",
                  "results": [],
                  "strategyProbeReport": {
                    "suiteId": "full_matrix_v1",
                    "tcpCandidates": [],
                    "quicCandidates": [],
                    "recommendation": {
                      "tcpCandidateId": "tcp-1",
                      "tcpCandidateLabel": "TCP candidate",
                      "quicCandidateId": "quic-1",
                      "quicCandidateLabel": "QUIC candidate",
                      "rationale": "best path",
                      "recommendedProxyConfigJson": "{}"
                    }
                  }
                }
                """.trimIndent(),
            )

        assertNull(report.strategyProbeReport?.auditAssessment)
    }

    @Test
    fun `new scan request round trips profile metadata and target packs`() {
        val request =
            ScanRequest(
                profileId = "ru-web-connectivity",
                displayName = "Russia Web Connectivity",
                pathMode = ScanPathMode.RAW_PATH,
                family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
                regionTag = "ru",
                manualOnly = true,
                packRefs = listOf("ru-independent-media@1", "ru-control@1"),
                serviceTargets =
                    listOf(
                        ServiceTarget(
                            id = "telegram",
                            service = "Telegram",
                            bootstrapUrl = "https://telegram.org/",
                            tcpEndpointHost = "telegram.org",
                        ),
                    ),
                circumventionTargets =
                    listOf(
                        CircumventionTarget(
                            id = "tor",
                            tool = "Tor",
                            bootstrapUrl = "https://www.torproject.org/download/",
                            handshakeHost = "www.torproject.org",
                        ),
                    ),
                throughputTargets =
                    listOf(
                        ThroughputTarget(
                            id = "youtube-web",
                            label = "YouTube Web",
                            url = "https://www.youtube.com/",
                            isControl = false,
                        ),
                    ),
            )

        val decoded =
            json.decodeFromString(ScanRequest.serializer(), json.encodeToString(ScanRequest.serializer(), request))

        assertEquals(DiagnosticProfileFamily.WEB_CONNECTIVITY, decoded.family)
        assertEquals("ru", decoded.regionTag)
        assertTrue(decoded.manualOnly)
        assertEquals(listOf("ru-independent-media@1", "ru-control@1"), decoded.packRefs)
        assertEquals("Telegram", decoded.serviceTargets.single().service)
        assertEquals("Tor", decoded.circumventionTargets.single().tool)
        assertEquals("YouTube Web", decoded.throughputTargets.single().label)
    }

    @Test
    fun `new scan report round trips diagnoses and pack versions`() {
        val report =
            ScanReport(
                sessionId = "session-1",
                profileId = "ru-dpi-full",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 1,
                finishedAt = 2,
                summary = "1 diagnosis",
                diagnoses =
                    listOf(
                        Diagnosis(
                            code = "dns_tampering",
                            summary = "DNS answers differ",
                            severity = "negative",
                            target = "meduza.io",
                            evidence = listOf("udpAddresses=203.0.113.10"),
                        ),
                    ),
                classifierVersion = "ru_ooni_v1",
                packVersions = mapOf("ru-independent-media" to 1, "ru-control" to 1),
            )

        val decoded =
            json.decodeFromString(ScanReport.serializer(), json.encodeToString(ScanReport.serializer(), report))

        assertEquals("ru_ooni_v1", decoded.classifierVersion)
        assertEquals(1, decoded.diagnoses.size)
        assertEquals("dns_tampering", decoded.diagnoses.single().code)
        assertEquals(1, decoded.packVersions["ru-independent-media"])
    }

    @Test
    fun `new strategy probe audit assessment round trips through scan report`() {
        val report =
            ScanReport(
                sessionId = "session-1",
                profileId = "automatic-audit",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 1,
                finishedAt = 2,
                summary = "audit complete",
                strategyProbeReport =
                    StrategyProbeReport(
                        suiteId = "full_matrix_v1",
                        tcpCandidates = emptyList(),
                        quicCandidates = emptyList(),
                        recommendation =
                            StrategyProbeRecommendation(
                                tcpCandidateId = "tcp-1",
                                tcpCandidateLabel = "TCP candidate",
                                quicCandidateId = "quic-1",
                                quicCandidateLabel = "QUIC candidate",
                                rationale = "best path",
                                recommendedProxyConfigJson = "{}",
                            ),
                        auditAssessment =
                            StrategyProbeAuditAssessment(
                                dnsShortCircuited = false,
                                coverage =
                                    StrategyProbeAuditCoverage(
                                        tcpCandidatesPlanned = 11,
                                        tcpCandidatesExecuted = 8,
                                        tcpCandidatesSkipped = 1,
                                        tcpCandidatesNotApplicable = 0,
                                        quicCandidatesPlanned = 2,
                                        quicCandidatesExecuted = 2,
                                        quicCandidatesSkipped = 0,
                                        quicCandidatesNotApplicable = 0,
                                        tcpWinnerSucceededTargets = 3,
                                        tcpWinnerTotalTargets = 3,
                                        quicWinnerSucceededTargets = 1,
                                        quicWinnerTotalTargets = 1,
                                        matrixCoveragePercent = 77,
                                        winnerCoveragePercent = 100,
                                    ),
                                confidence =
                                    StrategyProbeAuditConfidence(
                                        level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                        score = 75,
                                        rationale = "Audit rationale",
                                        warnings =
                                            listOf(
                                                "TCP matrix coverage stayed below 75% of planned candidates.",
                                            ),
                                    ),
                            ),
                    ),
            )

        val decoded =
            json.decodeFromString(ScanReport.serializer(), json.encodeToString(ScanReport.serializer(), report))

        assertEquals(
            StrategyProbeAuditConfidenceLevel.MEDIUM,
            decoded.strategyProbeReport
                ?.auditAssessment
                ?.confidence
                ?.level,
        )
        assertEquals(
            77,
            decoded.strategyProbeReport
                ?.auditAssessment
                ?.coverage
                ?.matrixCoveragePercent,
        )
    }

    @Test
    fun `engine scan report decodes tcp blocked16 status emitted by rust engine`() {
        val report =
            json.decodeEngineScanReportWireCompat(
                """
                {
                  "schemaVersion": 2,
                  "sessionId": "session-1",
                  "profileId": "default",
                  "pathMode": "RAW_PATH",
                  "startedAt": 1,
                  "finishedAt": 2,
                  "summary": "done",
                  "results": [],
                  "observations": [
                    {
                      "kind": "TCP",
                      "target": "fixture-http",
                      "tcp": {
                        "provider": "fixture-http",
                        "status": "BLOCKED16_KB",
                        "bytesSent": 16384,
                        "responsesSeen": 0
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val observation = report.observations.single()
        assertEquals(ObservationKind.TCP, observation.kind)
        assertEquals(TcpProbeStatus.BLOCKED_16KB, observation.tcp?.status)
    }
}
