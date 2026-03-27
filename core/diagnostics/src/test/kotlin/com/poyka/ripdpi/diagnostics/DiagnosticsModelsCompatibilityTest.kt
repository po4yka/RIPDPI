package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    fun `current profile spec decodes explicit probe persistence policy`() {
        val profile =
            json.decodeFromString(
                ProfileSpecWire.serializer(),
                """
                {
                  "profileId": "automatic-probing",
                  "displayName": "Automatic probing",
                  "kind": "STRATEGY_PROBE",
                  "family": "AUTOMATIC_PROBING",
                  "executionPolicy": {
                    "manualOnly": false,
                    "allowBackground": true,
                    "requiresRawPath": true,
                    "probePersistencePolicy": "BACKGROUND_ONLY"
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            ProbePersistencePolicyWire.BACKGROUND_ONLY,
            profile.normalizedExecutionPolicy().probePersistencePolicy,
        )
        assertTrue(profile.strategyProbeTargetCohorts.isEmpty())
    }

    @Test
    fun `current profile spec round trips strategy probe target cohorts`() {
        val profile =
            ProfileSpecWire(
                profileId = "automatic-audit",
                displayName = "Automatic audit",
                kind = ScanKind.STRATEGY_PROBE,
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                executionPolicy =
                    ProfileExecutionPolicyWire(
                        manualOnly = true,
                        allowBackground = false,
                        requiresRawPath = true,
                        probePersistencePolicy = ProbePersistencePolicyWire.MANUAL_ONLY,
                    ),
                strategyProbe = StrategyProbeRequest(suiteId = "full_matrix_v1"),
                strategyProbeTargetCohorts =
                    listOf(
                        com.poyka.ripdpi.diagnostics.contract.profile.StrategyProbeTargetCohortWire(
                            id = "global-core",
                            label = "Global core",
                            domainTargets =
                                listOf(
                                    DomainTarget(host = "www.youtube.com"),
                                    DomainTarget(host = "discord.com"),
                                    DomainTarget(host = "proton.me"),
                                ),
                            quicTargets =
                                listOf(
                                    QuicTarget(host = "www.youtube.com"),
                                    QuicTarget(host = "discord.com"),
                                ),
                        ),
                    ),
            )

        val decoded =
            json.decodeFromString(
                ProfileSpecWire.serializer(),
                json.encodeToString(ProfileSpecWire.serializer(), profile),
            )

        assertEquals(1, decoded.strategyProbeTargetCohorts.size)
        assertEquals("global-core", decoded.strategyProbeTargetCohorts.single().id)
    }

    @Test
    fun `engine progress wire round trips strategy probe progress`() {
        val progress =
            EngineProgressWire(
                sessionId = "session-1",
                phase = "tcp",
                completedSteps = 2,
                totalSteps = 14,
                message = "Testing TCP candidate",
                isFinished = false,
                latestProbeTarget = "TCP fake TLS",
                latestProbeOutcome = "success",
                strategyProbeProgress =
                    StrategyProbeLiveProgress(
                        lane = StrategyProbeProgressLane.TCP,
                        candidateIndex = 3,
                        candidateTotal = 14,
                        candidateId = "tcp_fake_tls",
                        candidateLabel = "TCP fake TLS",
                    ),
            )

        val encoded = json.encodeToString(EngineProgressWire.serializer(), progress)
        val decoded = json.decodeFromString(EngineProgressWire.serializer(), encoded)

        assertEquals(StrategyProbeProgressLane.TCP, decoded.strategyProbeProgress?.lane)
        assertEquals(3, decoded.strategyProbeProgress?.candidateIndex)
        assertEquals("TCP fake TLS", decoded.strategyProbeProgress?.candidateLabel)
    }

    @Test
    fun `engine scan request wire round trips probe tasks and target packs`() {
        val request =
            EngineScanRequestWire(
                profileId = "ru-web-connectivity",
                displayName = "Russia Web Connectivity",
                pathMode = ScanPathMode.RAW_PATH,
                family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
                regionTag = "ru",
                packRefs = listOf("ru-independent-media@1", "ru-control@1"),
                probeTasks =
                    listOf(
                        EngineProbeTaskWire(
                            family = EngineProbeTaskFamily.WEB,
                            targetId = "meduza",
                            label = "Meduza",
                        ),
                    ),
                serviceTargets =
                    listOf(
                        ServiceTarget(
                            id = "telegram",
                            service = "Telegram",
                            bootstrapUrl = "https://telegram.org/",
                            tcpEndpointHost = "telegram.org",
                        ),
                    ),
            )

        val decoded =
            json.decodeFromString(
                EngineScanRequestWire.serializer(),
                json.encodeToString(EngineScanRequestWire.serializer(), request),
            )

        assertEquals("ru", decoded.regionTag)
        assertEquals(listOf("ru-independent-media@1", "ru-control@1"), decoded.packRefs)
        assertEquals(EngineProbeTaskFamily.WEB, decoded.probeTasks.single().family)
        assertEquals("telegram", decoded.serviceTargets.single().id)
    }

    @Test
    fun `engine scan report decodes tcp blocked16 status emitted by rust engine`() {
        val report =
            json.decodeEngineScanReportWire(
                """
                {
                  "schemaVersion": 1,
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
