package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.StrategyProbeTargetCohortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsConnectivityPlannerTest {
    @Test
    fun `connectivity planner selects one JSON survey per requested DNS domain`() {
        val settings = defaultDiagnosticsAppSettings()
        val intent =
            strategyProbeIntent(settings, null)
                .copy(
                    kind = ScanKind.CONNECTIVITY,
                    strategyProbe = null,
                    dnsTargets = listOf(DnsTarget("Selected.Example"), DnsTarget("selected.example")),
                )
        val context = strategyProbeContext(settings, null)
        val encoder = DefaultEngineRequestEncoder()
        val request = encoder.encode(DefaultDiagnosticsPlanner().plan(intent, context))

        assertEquals(1, request.probeTasks.count { it.family == EngineProbeTaskFamily.DOH_JSON_SURVEY })
        assertEquals(
            "Selected.Example",
            request.probeTasks.first { it.family == EngineProbeTaskFamily.DOH_JSON_SURVEY }.targetId,
        )
        assertTrue(request.probeTasks.any { it.family == EngineProbeTaskFamily.DNS })

        val noDnsRequest =
            encoder.encode(DefaultDiagnosticsPlanner().plan(intent.copy(dnsTargets = emptyList()), context))
        assertTrue(noDnsRequest.probeTasks.none { it.family == EngineProbeTaskFamily.DOH_JSON_SURVEY })
    }

    @Test
    fun `connectivity planner canonicalizes QUIC SNI and matches the task id`() {
        val settings = defaultDiagnosticsAppSettings()
        val intent =
            strategyProbeIntent(settings, null)
                .copy(
                    kind = ScanKind.CONNECTIVITY,
                    strategyProbe = null,
                    quicTargets =
                        listOf(
                            QuicTarget(host = "Bücher.Example.", connectIp = "192.0.2.1"),
                            QuicTarget(host = "faß.example", port = IpProbeMaxPort),
                            QuicTarget(host = "192.0.2.33"),
                            QuicTarget(host = "2001:DB8::1"),
                            QuicTarget(host = "bad label.example", port = 0),
                            QuicTarget(host = "foo%7A.example", port = -1),
                            QuicTarget(host = "overflow.example", port = IpProbeMaxPort + 1),
                        ),
                )
        val context = strategyProbeContext(settings, null)
        val request = DefaultEngineRequestEncoder().encode(DefaultDiagnosticsPlanner().plan(intent, context))

        val expectedHosts =
            listOf(
                "xn--bcher-kva.example.",
                "xn--fa-hia.example",
                "192.0.2.33",
                "2001:DB8::1",
                "bad label.example",
                "foo%7A.example",
                "overflow.example",
            )
        assertEquals(expectedHosts, request.quicTargets.map(QuicTarget::host))
        val quicTaskHosts = request.probeTasks.filter { it.family == EngineProbeTaskFamily.QUIC }.map { it.targetId }
        assertEquals(expectedHosts, quicTaskHosts)
        assertEquals("192.0.2.1", request.quicTargets.first().connectIp)
        assertEquals(
            listOf(443, IpProbeMaxPort, 443, 443, 0, 0, 0),
            request.quicTargets.map(QuicTarget::port),
        )
    }
}

internal fun strategyProbeIntent(
    settings: com.poyka.ripdpi.proto.AppSettings,
    baseProxyConfigJson: String?,
    profileId: String = "strategy-probe",
    family: DiagnosticProfileFamily = DiagnosticProfileFamily.AUTOMATIC_PROBING,
    suiteId: String = "quick_v1",
    domainTargets: List<DomainTarget> = listOf(DomainTarget(host = "example.org")),
    quicTargets: List<QuicTarget> = listOf(QuicTarget(host = "example.org")),
    strategyProbeTargetCohorts: List<StrategyProbeTargetCohortSpec> = emptyList(),
) = DiagnosticsIntent(
    profileId = profileId,
    displayName = "Strategy probe",
    settings = settings,
    kind = ScanKind.STRATEGY_PROBE,
    family = family,
    regionTag = null,
    executionPolicy =
        ExecutionPolicy(
            manualOnly = false,
            allowBackground = false,
            requiresRawPath = true,
            probePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
        ),
    packRefs = emptyList(),
    domainTargets = domainTargets,
    dnsTargets = emptyList(),
    tcpTargets = emptyList(),
    quicTargets = quicTargets,
    serviceTargets = emptyList(),
    circumventionTargets = emptyList(),
    throughputTargets = emptyList(),
    whitelistSni = emptyList(),
    telegramTarget = null,
    strategyProbe = StrategyProbeRequest(suiteId = suiteId, baseProxyConfigJson = baseProxyConfigJson),
    strategyProbeTargetCohorts = strategyProbeTargetCohorts,
    requestedPathMode = ScanPathMode.RAW_PATH,
)
