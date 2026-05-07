package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.ui.diagnostics.StrategySignaturePresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsUiStrategyPresentersTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `strategy report summary mapper owns high level report metrics`() {
        val metrics = StrategyReportSummaryMapper().summaryMetrics(strategyPresenterReport())

        assertEquals("Worked", metrics[0].label)
        assertEquals("3", metrics[0].value)
        assertEquals(DiagnosticsTone.Positive, metrics[0].tone)
    }

    @Test
    fun `candidate detail mapper owns candidate detail mapping`() {
        val details =
            CandidateDetailMapper().candidateDetails(
                factory = support,
                report = strategyPresenterReport(),
                reportResults = emptyList(),
                serviceMode = "VPN",
            )

        val candidate = requireNotNull(details["tcp-1"])
        assertEquals("tcp-1", candidate.id)
        assertEquals("TCP baseline", candidate.label)
        assertTrue(candidate.metrics.any { it.label == "Emitter" })
    }

    @Test
    fun `audit assessment presenter owns strategy report presentation`() {
        val presentation =
            AuditAssessmentPresenter().reportPresentation(
                factory = support,
                report = strategyPresenterReport(),
                winningPath = null,
            )

        assertEquals("Manual apply", presentation.manualApplyBadge)
        assertNotNull(presentation.statusLabel)
    }

    @Test
    fun `resolver recommendation presenter owns resolver fields`() {
        val uiModel =
            ResolverRecommendationPresenter().toUiModel(
                ResolverRecommendation(
                    triggerOutcome = "dns_timeout",
                    selectedResolverId = "adguard",
                    selectedProtocol = "doh",
                    selectedEndpoint = "https://dns.example/dns-query",
                    selectedBootstrapIps = listOf("94.140.14.14"),
                    rationale = "Encrypted resolver recovered the path.",
                    appliedTemporarily = true,
                    persistable = true,
                ),
            )

        assertEquals("Switch DNS to Adguard", uiModel.headline)
        assertEquals("DOH", uiModel.fields.first { it.label == "Protocol" }.value)
        assertEquals("94.140.14.14", uiModel.fields.first { it.label == "Bootstrap" }.value)
        assertTrue(uiModel.appliedTemporarily)
        assertTrue(uiModel.persistable)
    }

    @Test
    fun `strategy signature presenter owns signature fields`() {
        val fields =
            StrategySignaturePresenter().fields(
                BypassStrategySignature(
                    mode = "VPN",
                    configSource = "UI",
                    hostAutolearn = "Enabled",
                    desyncMethod = "split",
                    chainSummary = "split@host",
                    tcpStrategyFamily = "hostfake",
                    protocolToggles = listOf("TCP", "QUIC"),
                    tlsRecordSplitEnabled = true,
                    fakeTtlMode = "adaptive_custom",
                    adaptiveFakeTtlBias = 2,
                    routeGroup = "default",
                ),
            )

        assertEquals("VPN", fields.first { it.label == "Mode" }.value)
        assertEquals("TCP/QUIC", fields.first { it.label == "Protocols" }.value)
        assertEquals("Custom adaptive TTL", fields.first { it.label == "Fake TTL mode" }.value)
        assertEquals("Prefer higher TTLs first (+2)", fields.first { it.label == "Adaptive fake TTL bias" }.value)
    }
}

private fun strategyPresenterReport(): StrategyProbeReport =
    StrategyProbeReport(
        suiteId = StrategyProbeSuiteFullMatrixV1,
        tcpCandidates =
            listOf(
                strategyPresenterCandidate(id = "tcp-1", label = "TCP baseline", family = "baseline_current"),
                strategyPresenterCandidate(id = "tcp-2", label = "TCP winner", family = "hostfake"),
            ),
        quicCandidates =
            listOf(
                strategyPresenterCandidate(id = "quic-1", label = "QUIC baseline", family = "quic_disabled"),
            ),
        recommendation =
            StrategyProbeRecommendation(
                tcpCandidateId = "tcp-2",
                tcpCandidateLabel = "TCP winner",
                quicCandidateId = "quic-1",
                quicCandidateLabel = "QUIC baseline",
                rationale = "Best combined recovery across lanes.",
                recommendedProxyConfigJson = """{"kind":"ui"}""",
            ),
    )

private fun strategyPresenterCandidate(
    id: String,
    label: String,
    family: String,
): StrategyProbeCandidateSummary =
    StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
        outcome = "success",
        rationale = "Recovered target set.",
        succeededTargets = 1,
        totalTargets = 1,
        weightedSuccessScore = 10,
        totalWeight = 10,
        qualityScore = 10,
    )
