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

class PmtuProbePlanningTest {
    @Test
    fun `optional request preserves legacy serialization and keeps independent profile config`() {
        val legacy =
            EngineScanRequestWire(profileId = "legacy", displayName = "Legacy", pathMode = ScanPathMode.RAW_PATH)
        val json = Json { encodeDefaults = true }
        assertFalse(json.encodeToString(EngineScanRequestWire.serializer(), legacy).contains("pmtuProbe"))
        val configured = legacy.copy(pmtuProbe = PmtuProbeConfig())
        assertEquals(
            configured,
            json.decodeFromString(
                EngineScanRequestWire.serializer(),
                json.encodeToString(EngineScanRequestWire.serializer(), configured),
            ),
        )
    }

    @Test
    fun `planner schedules only the explicit PMTU request`() {
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
        assertEquals(listOf(EngineProbeTaskFamily.PMTU), request.probeTasks.map { it.family })
        assertEquals(PmtuProbeConfig(), request.pmtuProbe)
        assertEquals(PmtuProbeConfig(), intent.toProfileProjection().pmtuProbe)
        val legacy =
            DefaultDiagnosticsPlanner()
                .plan(
                    intent.copy(pmtuProbe = null),
                    context,
                ).toEngineScanRequestWire()
        assertNull(legacy.pmtuProbe)
        assertTrue(legacy.probeTasks.isEmpty())
    }

    @Test
    fun `safety rejects automatic proxy and malformed PMTU requests`() {
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
            PmtuProbeConfig(host = "bad host"),
            PmtuProbeConfig(connectIpv4 = "example.com"),
            PmtuProbeConfig(connectIpv6 = "192.0.2.1"),
            PmtuProbeConfig(connectIpv6 = "::ffff:192.0.2.1"),
            PmtuProbeConfig(host = "::ffff:c000:0201"),
            PmtuProbeConfig(observationMs = 15_001),
            PmtuProbeConfig(upperBoundUdpPayloadBytes = 1_200),
            PmtuProbeConfig(timeoutMs = Long.MAX_VALUE),
            PmtuProbeConfig(upperBoundUdpPayloadBytes = 1_473),
        ).forEach { assertTrue(runCatching { it.validate() }.isFailure) }
    }

    @Test
    fun `network change revokes both healthy outcome and typed evidence`() {
        val evidence = pmtuEvidenceForTest()
        val report =
            EngineScanReportWire(
                sessionId = "s",
                profileId = PmtuProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "PMTU",
                results =
                    listOf(
                        EngineProbeResultWire(
                            "pmtu",
                            "control",
                            "pmtu_observed",
                            listOf(
                                ProbeDetail(
                                    "pmtuEvidence",
                                    Json.encodeToString(PmtuProbeEvidence.serializer(), evidence),
                                ),
                            ),
                        ),
                    ),
            ).withoutNetworkScopeAuthority()
        val result = report.results.single()
        assertEquals("pmtu_not_observed", result.outcome)
        val scoped = requireNotNull(parsePmtuProbeEvidence(result.details))
        assertEquals(PmtuProbeStatus.NOT_OBSERVED, scoped.status)
        assertEquals("network_scope_unverified", scoped.reason)
        assertTrue(scoped.tlsValidated)
        assertTrue(scoped.observationWindowComplete)
    }

    @Test
    fun `full analysis includes raw PMTU stage and quick analysis does not imply it ran`() {
        assertEquals(
            ScanPathMode.RAW_PATH,
            HomeCompositeStageSpecs.single { it.profileId == PmtuProfileId }.pathMode,
        )
        assertTrue(QuickScanStageSpecs.none { it.profileId == PmtuProfileId })
        assertEquals(DiagnosticsOutcomeBucket.Healthy, bucketPmtu("pmtu_observed"))
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketPmtu("pmtu_unavailable"))
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketPmtu("pmtu_inconclusive"))
    }

    private fun intent() =
        DiagnosticsIntent(
            profileId = PmtuProfileId,
            displayName = "PMTU",
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
            pmtuProbe = PmtuProbeConfig(),
        )
}
