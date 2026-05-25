package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveProbeSafetyPolicyTest {
    private val policy =
        ActiveProbeSafetyPolicy(
            automaticHandoverProbeCooldownMs = 24L,
            automaticStrategyFailureProbeCooldownMs = 4L,
            automaticStrategyMaxCandidates = 5,
            automaticAuditDomainTargets = 3,
            automaticAuditQuicTargets = 2,
            manualStrategyDomainTargets = 12,
            manualStrategyQuicTargets = 8,
        )

    @Test
    fun `strategy failures use shorter automatic probe cooldown`() {
        assertEquals(
            4L,
            policy.cooldownMsForHandoverClassification(AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE),
        )
        assertEquals(24L, policy.cooldownMsForHandoverClassification("transport_switch"))
    }

    @Test
    fun `automatic strategy probes receive a max candidate budget`() {
        assertEquals(
            5,
            policy.enforceMaxCandidates(
                scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                requestedMaxCandidates = null,
            ),
        )
        assertEquals(
            5,
            policy.enforceMaxCandidates(
                scanOrigin = DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE,
                requestedMaxCandidates = 99,
            ),
        )
        assertNull(
            policy.enforceMaxCandidates(
                scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                requestedMaxCandidates = null,
            ),
        )
    }

    @Test
    fun `automatic target budget caps and mirrors target selection`() {
        val intent =
            strategyIntent(
                domainHosts = listOf("a.example", "b.example", "a.example", "c.example", "d.example"),
                quicHosts = listOf("qa.example", "qb.example", "qa.example", "qc.example"),
                targetSelection =
                    StrategyProbeTargetSelection(
                        cohortId = "oversized",
                        cohortLabel = "Oversized",
                        domainHosts = emptyList(),
                        quicHosts = emptyList(),
                    ),
            )

        val capped =
            policy.enforceTargetBudget(
                intent = intent,
                scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                isManual = false,
            )

        assertEquals(listOf("a.example", "b.example", "c.example"), capped.domainTargets.map(DomainTarget::host))
        assertEquals(listOf("qa.example", "qb.example"), capped.quicTargets.map(QuicTarget::host))
        assertEquals(capped.domainTargets.map(DomainTarget::host), capped.strategyProbe?.targetSelection?.domainHosts)
        assertEquals(capped.quicTargets.map(QuicTarget::host), capped.strategyProbe?.targetSelection?.quicHosts)
    }

    @Test
    fun `manual strategy probes keep a wider but bounded target set`() {
        val intent =
            strategyIntent(
                domainHosts = (1..14).map { index -> "domain-$index.example" },
                quicHosts = (1..10).map { index -> "quic-$index.example" },
            )

        val capped =
            policy.enforceTargetBudget(
                intent = intent,
                scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                isManual = true,
            )

        assertEquals(12, capped.domainTargets.size)
        assertEquals(8, capped.quicTargets.size)
    }

    private fun strategyIntent(
        domainHosts: List<String>,
        quicHosts: List<String>,
        targetSelection: StrategyProbeTargetSelection? = null,
    ) = DiagnosticsIntent(
        profileId = "strategy-probe",
        displayName = "Strategy probe",
        settings = defaultDiagnosticsAppSettings(),
        kind = ScanKind.STRATEGY_PROBE,
        family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
        regionTag = null,
        executionPolicy =
            ExecutionPolicy(
                manualOnly = false,
                allowBackground = true,
                requiresRawPath = true,
                probePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
            ),
        packRefs = emptyList(),
        domainTargets = domainHosts.map { host -> DomainTarget(host = host) },
        dnsTargets = emptyList(),
        tcpTargets = emptyList(),
        quicTargets = quicHosts.map { host -> QuicTarget(host = host) },
        serviceTargets = emptyList(),
        circumventionTargets = emptyList(),
        throughputTargets = emptyList(),
        whitelistSni = emptyList(),
        telegramTarget = null,
        strategyProbe = StrategyProbeRequest(suiteId = "quick_v1", targetSelection = targetSelection),
        requestedPathMode = ScanPathMode.RAW_PATH,
    )
}
