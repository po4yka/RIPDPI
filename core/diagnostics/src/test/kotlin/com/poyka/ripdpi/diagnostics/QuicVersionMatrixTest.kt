package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.diagnostics.finalization.withoutNetworkScopeAuthority
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicVersionMatrixTest {
    private val config =
        QuicVersionMatrixConfig(
            host = "quic.example",
            connectIp = "203.0.113.7",
            controlSni = "control.example",
            testSni = "test.example",
        )

    // Same shape as the native runner emits (ripdpi-diagnostics-runner connectivity/quic_version.rs).
    private val limits = """"limits":["initial_stage_only","local_port_separation_only"]"""
    private val v1Json =
        """{"status":"TEST_NOT_ANSWERED","version":"V1","reason":null,"stop":null,$limits,"cells":[""" +
            """{"sequence":0,"repetition":0,"sni":"CONTROL","stage":"VALID_INITIAL","bytesSent":1200,""" +
            """"bytesReceived":1252},{"sequence":1,"repetition":0,"sni":"TEST","stage":"TIMEOUT",""" +
            """"bytesSent":1200,"bytesReceived":0}],"residual":null}"""
    private val comparisonJson =
        """{"status":"DIFFERENCE","version":null,"reason":null,"stop":null,$limits,"cells":[],"residual":null}"""

    private fun details(json: String) = listOf(ProbeDetail(QuicVersionEvidenceKey, json))

    @Test
    fun `config needs an endpoint, an optional IP and two different DNS names`() {
        assertTrue(config.isValid())
        assertTrue(config.copy(connectIp = null, residualVersion = 2).isValid())
        listOf(
            QuicVersionMatrixConfig(),
            config.copy(host = "bad host"),
            config.copy(connectIp = "quic.example"),
            config.copy(controlSni = "192.0.2.1"),
            config.copy(testSni = "CONTROL.example"),
            config.copy(residualVersion = 3),
        ).forEach { assertFalse(it.toString(), it.isValid()) }
    }

    @Test
    fun `planner sends the user endpoint only for the manual raw path run`() {
        val intent = intent()
        val snapshot = FakeDiagnosticsContextProvider().captureContextForTest()
        val context =
            ScanContext(
                intent.settings,
                ScanPathMode.RAW_PATH,
                null,
                null,
                networkSnapshot = null,
                serviceMode = Mode.VPN.name,
                contextSnapshot = snapshot,
                approachSnapshot = createStoredApproachSnapshot(RipDpiJson, intent.settings, null, snapshot),
            )
        val request = DefaultDiagnosticsPlanner().plan(intent, context).toEngineScanRequestWire()
        assertEquals(listOf(EngineProbeTaskFamily.QUIC_VERSION_MATRIX), request.probeTasks.map { it.family })
        assertEquals(config, request.quicVersionMatrix)

        val policy = ActiveProbeSafetyPolicy()
        assertEquals(intent, policy.enforceTargetBudget(intent, DiagnosticsScanOrigin.USER_INITIATED, true))
        listOf(
            intent.copy(requestedPathMode = ScanPathMode.IN_PATH),
            intent.copy(quicVersionMatrix = QuicVersionMatrixConfig()),
        ).forEach {
            assertTrue(
                runCatching { policy.enforceTargetBudget(it, DiagnosticsScanOrigin.USER_INITIATED, true) }.isFailure,
            )
        }
        DiagnosticsScanOrigin.entries.filterNot { it == DiagnosticsScanOrigin.USER_INITIATED }.forEach {
            assertTrue(runCatching { policy.enforceTargetBudget(intent, it, true) }.isFailure)
        }
    }

    @Test
    fun `export keeps canonical evidence and drops unknown fields`() {
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                QuicVersionV1ProbeType,
                "quic.example",
                "quic_version_test_not_answered",
                Json.encodeToString(
                    ListSerializer(ProbeDetail.serializer()),
                    details(v1Json.replace("\"residual\":null", "\"residual\":null,\"localPort\":40000")),
                ),
                1,
            )
        val redacted = DiagnosticsArchiveRedactor(Json).redact(entity)
        assertFalse(redacted.detailJson.contains("40000"))
        assertFalse(redacted.detailJson.contains("test.example"))
        val exported = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), redacted.detailJson)
        val evidence = requireNotNull(parseQuicVersionMatrixEvidence(exported))
        assertEquals("TEST_NOT_ANSWERED", evidence.status)
        assertEquals(listOf(0, 1), evidence.cells.map { it.sequence })
        assertEquals(listOf("initial_stage_only", "local_port_separation_only"), evidence.limits)
    }

    @Test
    fun `network change withdraws version claims and keeps the cells`() {
        val report =
            EngineScanReportWire(
                sessionId = "s",
                profileId = QuicVersionMatrixProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "QUIC version",
                results =
                    listOf(
                        EngineProbeResultWire(
                            QuicVersionV1ProbeType,
                            "quic.example",
                            "quic_version_test_not_answered",
                            details(v1Json),
                        ),
                        EngineProbeResultWire(
                            QuicVersionComparisonProbeType,
                            "quic.example",
                            "quic_version_difference_observed",
                            details(comparisonJson),
                        ),
                    ),
            ).withoutNetworkScopeAuthority()
        val (v1, comparison) = report.results
        assertEquals("quic_version_inconclusive", v1.outcome)
        assertEquals("quic_version_difference_inconclusive", comparison.outcome)
        val v1Evidence = requireNotNull(parseQuicVersionMatrixEvidence(v1.details))
        assertEquals("INCONCLUSIVE", v1Evidence.status)
        assertEquals(2, v1Evidence.cells.size)
        assertTrue("network_changed" in v1Evidence.limits)
        assertEquals("INCONCLUSIVE", parseQuicVersionMatrixEvidence(comparison.details)?.status)

        val unsupported =
            EngineProbeResultWire(
                QuicVersionMatrixProbeType,
                "quic.example",
                "quic_version_unsupported",
                details("""{"status":"UNSUPPORTED","reason":"proxy_path"}"""),
            )
        assertEquals(
            unsupported,
            report
                .copy(results = listOf(unsupported))
                .withoutNetworkScopeAuthority()
                .results
                .single(),
        )
    }

    @Test
    fun `buckets never call an unanswered version blocked and residual stays apart`() {
        assertEquals(DiagnosticsOutcomeBucket.Healthy, bucketQuicVersionMatrix("quic_version_test_answered"))
        assertEquals(DiagnosticsOutcomeBucket.Attention, bucketQuicVersionMatrix("quic_version_difference_observed"))
        assertEquals(
            DiagnosticsOutcomeBucket.Inconclusive,
            bucketQuicVersionMatrix("quic_version_unsupported_by_endpoint"),
        )
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketQuicVersionMatrix("quic_version_inconclusive"))
        val lines =
            listOf(
                ProbeResult(QuicVersionV1ProbeType, "quic.example", "quic_version_test_not_answered", details(v1Json)),
                ProbeResult(
                    QuicVersionComparisonProbeType,
                    "quic.example",
                    "quic_version_difference_observed",
                    details(comparisonJson),
                ),
            ).quicVersionMatrixSummaryLines()
        assertEquals(
            listOf(
                "quicVersion v1=TEST_NOT_ANSWERED; comparison=DIFFERENCE; " +
                    "limits=initial_stage_only,local_port_separation_only",
            ),
            lines,
        )
    }

    @Test
    fun `evidence with unknown tokens is rejected`() {
        assertNull(parseQuicVersionMatrixEvidence(v1Json.replace("\"TIMEOUT\"", "\"BLOCKED\"")))
        assertNull(parseQuicVersionMatrixEvidence(v1Json.replace("\"V1\"", "\"V3\"")))
    }

    private fun intent() =
        DiagnosticsIntent(
            profileId = QuicVersionMatrixProfileId,
            displayName = "QUIC version matrix",
            settings = defaultDiagnosticsAppSettings(),
            kind = ScanKind.CONNECTIVITY,
            family = DiagnosticProfileFamily.GENERAL,
            regionTag = null,
            executionPolicy = ExecutionPolicy(true, false, true, ProbePersistencePolicy.MANUAL_ONLY),
            packRefs = emptyList(),
            domainTargets = emptyList(),
            dnsTargets = emptyList(),
            tcpTargets = emptyList(),
            quicTargets = emptyList(),
            serviceTargets = emptyList(),
            circumventionTargets = emptyList(),
            throughputTargets = emptyList(),
            whitelistSni = emptyList(),
            telegramTarget = null,
            strategyProbe = null,
            requestedPathMode = ScanPathMode.RAW_PATH,
            quicVersionMatrix = config,
        )
}
