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

class TlsHopComparisonTest {
    private val config = TlsHopComparisonConfig("203.0.113.7", "control.example", "test.example")

    // Same shape as the native runner emits (ripdpi-diagnostics-runner connectivity/tls_hop.rs).
    private val evidenceJson =
        """{"status":"DIVERGENCE","reason":null,"firstHop":5,"lastHop":5,"pathLength":9,""" +
            """"limits":["inconsistent_repetitions"],"stop":"COMPLETED","controlAttempts":18,"testAttempts":18}"""
    private val localJson =
        """{"controlSni":"control.example","testSni":"test.example","attempts":""" +
            """[{"ttl":5,"role":"TEST","repetition":0,"outcome":"RST","elapsedMs":731}]}"""
    private val details = listOf(ProbeDetail(TlsHopEvidenceKey, evidenceJson), ProbeDetail(TlsHopLocalKey, localJson))

    @Test
    fun `config needs an endpoint and two different DNS names`() {
        assertTrue(config.isValid())
        listOf(
            TlsHopComparisonConfig(),
            config.copy(host = "bad host"),
            config.copy(controlSni = "192.0.2.1"),
            config.copy(testSni = "CONTROL.example"),
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
        assertEquals(listOf(EngineProbeTaskFamily.TLS_HOP_COMPARISON), request.probeTasks.map { it.family })
        assertEquals(config, request.tlsHopComparison)

        val policy = ActiveProbeSafetyPolicy()
        assertEquals(intent, policy.enforceTargetBudget(intent, DiagnosticsScanOrigin.USER_INITIATED, true))
        listOf(
            intent.copy(requestedPathMode = ScanPathMode.IN_PATH),
            intent.copy(tlsHopComparison = TlsHopComparisonConfig()),
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
    fun `ordinary export drops per-attempt timings and keeps the interval`() {
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                TlsHopComparisonProbeType,
                "203.0.113.7",
                "tls_hop_divergence",
                Json.encodeToString(ListSerializer(ProbeDetail.serializer()), details),
                1,
            )
        val redacted = DiagnosticsArchiveRedactor(Json).redact(entity)
        assertFalse(redacted.detailJson.contains("elapsedMs"))
        assertFalse(redacted.detailJson.contains("731"))
        assertFalse(redacted.detailJson.contains(TlsHopLocalKey))
        val exported = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), redacted.detailJson)
        val evidence = requireNotNull(parseTlsHopComparisonEvidence(exported))
        assertEquals(TlsHopStatus.DIVERGENCE, evidence.status)
        assertEquals(listOf(5, 5, 9), listOf(evidence.firstHop, evidence.lastHop, evidence.pathLength))
        assertEquals(listOf("inconsistent_repetitions"), evidence.limits)
    }

    @Test
    fun `network change withdraws the interval and keeps the attempts`() {
        val report =
            EngineScanReportWire(
                sessionId = "s",
                profileId = TlsHopComparisonProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "TLS hop",
                results =
                    listOf(
                        EngineProbeResultWire(TlsHopComparisonProbeType, "203.0.113.7", "tls_hop_divergence", details),
                    ),
            ).withoutNetworkScopeAuthority()
        val result = report.results.single()
        assertEquals("tls_hop_inconclusive", result.outcome)
        val evidence = requireNotNull(parseTlsHopComparisonEvidence(result.details))
        assertEquals(TlsHopStatus.INCONCLUSIVE, evidence.status)
        assertNull(evidence.firstHop)
        assertNull(evidence.pathLength)
        assertTrue("network_changed" in evidence.limits)
        assertEquals(1, parseTlsHopComparisonLocal(result.details)?.attempts?.size)

        val unsupported =
            EngineProbeResultWire(
                TlsHopComparisonProbeType,
                "203.0.113.7",
                "tls_hop_unsupported",
                listOf(ProbeDetail(TlsHopEvidenceKey, """{"status":"UNSUPPORTED","reason":"ttl_setter"}""")),
            )
        assertEquals(
            unsupported,
            report
                .copy(results = listOf(unsupported))
                .withoutNetworkScopeAuthority()
                .results
                .single(),
        )
        assertEquals(DiagnosticsOutcomeBucket.Attention, bucketTlsHopComparison("tls_hop_divergence"))
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketTlsHopComparison("tls_hop_unsupported"))
    }

    @Test
    fun `evidence that claims an interval beyond the path is rejected`() {
        assertNull(parseTlsHopComparisonEvidence(evidenceJson.replace("\"pathLength\":9", "\"pathLength\":5")))
        assertNull(parseTlsHopComparisonEvidence(evidenceJson.replace("\"DIVERGENCE\"", "\"INCONCLUSIVE\"")))
    }

    private fun intent() =
        DiagnosticsIntent(
            profileId = TlsHopComparisonProfileId,
            displayName = "TLS hop comparison",
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
            tlsHopComparison = config,
        )
}
