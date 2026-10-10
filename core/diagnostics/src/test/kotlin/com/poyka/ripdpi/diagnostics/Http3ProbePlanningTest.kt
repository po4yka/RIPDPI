package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.finalization.withoutNetworkScopeAuthority
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Http3ProbePlanningTest {
    @Test
    fun `optional request preserves legacy serialization and keeps independent profile config`() {
        val legacy =
            EngineScanRequestWire(profileId = "legacy", displayName = "Legacy", pathMode = ScanPathMode.RAW_PATH)
        val json = Json { encodeDefaults = true }
        assertFalse(json.encodeToString(EngineScanRequestWire.serializer(), legacy).contains("http3Probe"))
        val configured = legacy.copy(http3Probe = Http3ProbeConfig())
        assertEquals(
            configured,
            json.decodeFromString(
                EngineScanRequestWire.serializer(),
                json.encodeToString(EngineScanRequestWire.serializer(), configured),
            ),
        )
    }

    @Test
    fun `planner schedules only the explicit HTTP3 request`() {
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
        assertEquals(listOf(EngineProbeTaskFamily.HTTP3), request.probeTasks.map { it.family })
        assertEquals(Http3ProbeConfig(), request.http3Probe)
        assertEquals(Http3ProbeConfig(), intent.toProfileProjection().http3Probe)
        val legacy =
            DefaultDiagnosticsPlanner()
                .plan(
                    intent.copy(http3Probe = null),
                    context,
                ).toEngineScanRequestWire()
        assertNull(legacy.http3Probe)
        assertTrue(legacy.probeTasks.isEmpty())
    }

    @Test
    fun `safety rejects automatic proxy and malformed HTTP3 requests`() {
        val policy = ActiveProbeSafetyPolicy()
        val intent = intent()
        assertEquals(intent, policy.enforceTargetBudget(intent, DiagnosticsScanOrigin.USER_INITIATED, true))
        assertTrue(
            runCatching { policy.enforceTargetBudget(intent, DiagnosticsScanOrigin.USER_INITIATED, false) }.isFailure,
        )
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(
                    intent.copy(requestedPathMode = ScanPathMode.IN_PATH),
                    DiagnosticsScanOrigin.USER_INITIATED,
                    true,
                )
            }.isFailure,
        )
        DiagnosticsScanOrigin.entries.filterNot { it == DiagnosticsScanOrigin.USER_INITIATED }.forEach {
            assertTrue(runCatching { policy.enforceTargetBudget(intent, it, true) }.isFailure)
        }
        listOf(
            Http3ProbeConfig(host = "bad host"),
            Http3ProbeConfig(connectIp = "example.com"),
            Http3ProbeConfig(path = "//example.com/path"),
            Http3ProbeConfig(path = "/?private=query"),
            Http3ProbeConfig(timeoutMs = Long.MAX_VALUE),
            Http3ProbeConfig(maxResponseBytes = 262_145),
        ).forEach { assertTrue(runCatching { it.validate() }.isFailure) }
    }

    @Test
    fun `network change revokes both healthy outcome and typed evidence`() {
        val evidence =
            Http3ProbeEvidence(
                stage = Http3ProbeStage.HTTP_BODY,
                status = Http3ProbeStatus.COMPLETE,
                dnsStatus = Http3DnsStatus.RESOLVED,
                tlsValidated = true,
                alpn = "h3",
                requestSent = true,
                http3Validated = true,
                httpStatus = 204,
                bodyComplete = true,
                handshakeElapsedMs = 10,
                headersElapsedMs = 20,
                durationMs = 20,
                attemptCount = 1,
            )
        val report =
            EngineScanReportWire(
                sessionId = "s",
                profileId = Http3ProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "HTTP3",
                results =
                    listOf(
                        EngineProbeResultWire(
                            "http3",
                            "control",
                            "http3_complete",
                            listOf(
                                ProbeDetail(
                                    "http3Evidence",
                                    Json.encodeToString(Http3ProbeEvidence.serializer(), evidence),
                                ),
                            ),
                        ),
                    ),
            ).withoutNetworkScopeAuthority()
        val result = report.results.single()
        assertEquals("http3_inconclusive", result.outcome)
        val scoped = requireNotNull(parseHttp3ProbeEvidence(result.details))
        assertEquals(Http3ProbeStatus.NOT_OBSERVED, scoped.status)
        assertEquals("network_scope_unverified", scoped.reason)
        assertTrue(scoped.http3Validated)
        assertTrue(scoped.bodyComplete)
    }

    @Test
    fun `full analysis includes raw HTTP3 stage and quick analysis does not imply it ran`() {
        assertEquals(
            ScanPathMode.RAW_PATH,
            HomeCompositeStageSpecs.single { it.profileId == Http3ProfileId }.pathMode,
        )
        assertTrue(QuickScanStageSpecs.none { it.profileId == Http3ProfileId })
        assertEquals(DiagnosticsOutcomeBucket.Healthy, bucketHttp3("http3_complete"))
        assertEquals(DiagnosticsOutcomeBucket.Attention, bucketHttp3("http3_unavailable"))
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketHttp3("http3_inconclusive"))
    }

    private fun intent() =
        DiagnosticsIntent(
            profileId = Http3ProfileId,
            displayName = "HTTP3",
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
            http3Probe = Http3ProbeConfig(),
        )
}
