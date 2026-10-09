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

class IpFamilyProbePlanningTest {
    @Test
    fun `optional request preserves legacy serialization and keeps independent profile config`() {
        val legacy =
            EngineScanRequestWire(profileId = "legacy", displayName = "Legacy", pathMode = ScanPathMode.RAW_PATH)
        val json = Json { encodeDefaults = true }
        assertFalse(json.encodeToString(EngineScanRequestWire.serializer(), legacy).contains("ipFamilyProbe"))
        val configured = legacy.copy(ipFamilyProbe = IpFamilyProbeConfig())
        assertEquals(
            configured,
            json.decodeFromString(
                EngineScanRequestWire.serializer(),
                json.encodeToString(EngineScanRequestWire.serializer(), configured),
            ),
        )
    }

    @Test
    fun `planner schedules only explicitly configured family and preserves paired controls`() {
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
        assertEquals(listOf(EngineProbeTaskFamily.IP_FAMILY), request.probeTasks.map { it.family })
        assertEquals(IpFamilyProbeConfig(), request.ipFamilyProbe)
        assertEquals(IpFamilyProbeConfig(), intent.toProfileProjection().ipFamilyProbe)
        val legacy =
            DefaultDiagnosticsPlanner()
                .plan(
                    intent.copy(ipFamilyProbe = null),
                    context,
                ).toEngineScanRequestWire()
        assertNull(legacy.ipFamilyProbe)
        assertTrue(legacy.probeTasks.isEmpty())
    }

    @Test
    fun `safety rejects automatic proxy and nonnumeric family probes`() {
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
        assertTrue(runCatching { IpFamilyProbeConfig(ipv4Address = "example.com").validate() }.isFailure)
        assertTrue(runCatching { IpFamilyProbeConfig(ipv6Address = "::ffff:1.1.1.1").validate() }.isFailure)
        assertTrue(runCatching { IpFamilyProbeConfig(timeoutMs = Long.MAX_VALUE).validate() }.isFailure)
        listOf("0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.0.170", "192.0.0.171").forEach {
            assertTrue(runCatching { IpFamilyProbeConfig(ipv4Address = it).validate() }.isFailure)
        }
        listOf("::", "ff02::1").forEach {
            assertTrue(runCatching { IpFamilyProbeConfig(ipv6Address = it).validate() }.isFailure)
        }
    }

    @Test
    fun `network change revokes both healthy outcome and typed evidence`() {
        val evidence =
            IpFamilyProbeEvidence(
                family = IpProbeFamily.IPV6,
                stage = IpProbeStage.TCP_CONNECT,
                status = IpProbeStatus.REACHABLE,
                attemptCount = 1,
            )
        val report =
            EngineScanReportWire(
                sessionId = "s",
                profileId = IpFamilyProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "IP",
                results =
                    listOf(
                        EngineProbeResultWire(
                            "ip_family",
                            "IPV6",
                            "ip_family_reachable",
                            listOf(
                                ProbeDetail(
                                    "ipFamilyEvidence",
                                    Json.encodeToString(IpFamilyProbeEvidence.serializer(), evidence),
                                ),
                            ),
                        ),
                    ),
            ).withoutNetworkScopeAuthority()
        val result = report.results.single()
        assertEquals("ip_family_inconclusive", result.outcome)
        val scoped = requireNotNull(parseIpFamilyProbeEvidence(result.details))
        assertEquals(IpProbeStatus.NOT_OBSERVED, scoped.status)
        assertEquals("network_scope_unverified", scoped.reason)
    }

    @Test
    fun `full analysis includes raw family stage and quick analysis does not imply it ran`() {
        assertEquals(
            ScanPathMode.RAW_PATH,
            HomeCompositeStageSpecs.single { it.profileId == IpFamilyProfileId }.pathMode,
        )
        assertTrue(QuickScanStageSpecs.none { it.profileId == IpFamilyProfileId })
        assertEquals(DiagnosticsOutcomeBucket.Healthy, bucketIpFamily("ip_family_reachable"))
        assertEquals(DiagnosticsOutcomeBucket.Attention, bucketIpFamily("ip_family_unavailable"))
        assertEquals(DiagnosticsOutcomeBucket.Inconclusive, bucketIpFamily("ip_family_inconclusive"))
    }

    private fun intent() =
        DiagnosticsIntent(
            profileId = IpFamilyProfileId,
            displayName = "IP family",
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
            ipFamilyProbe = IpFamilyProbeConfig(),
        )
}
