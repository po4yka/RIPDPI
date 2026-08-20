package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsStrategyProbeUiFactoryTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `full matrix report builds winning path from recommended tcp and quic candidates`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpWinnerId = "tcp-2",
                quicWinnerId = "quic-2",
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "VPN")

        val winningPath = requireNotNull(uiModel.winningPath)
        assertEquals("tcp-2", winningPath.tcpWinner.id)
        assertEquals("TCP winner", winningPath.tcpWinner.label)
        assertEquals(1, winningPath.tcpWinner.hiddenCandidateCount)
        assertEquals("quic-2", winningPath.quicWinner.id)
        assertEquals("QUIC winner", winningPath.quicWinner.label)
        assertEquals(1, winningPath.quicWinner.hiddenCandidateCount)
        assertEquals("System DNS", winningPath.dnsLaneLabel)
        assertNotNull(uiModel.candidateDetails[winningPath.tcpWinner.id])
        assertNotNull(uiModel.candidateDetails[winningPath.quicWinner.id])
    }

    @Test
    fun `candidate details preserve candidate and per candidate result ordering`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpWinnerId = "tcp-2",
                quicWinnerId = "quic-2",
            )
        val reportResults =
            listOf(
                strategyProbeResult(candidateId = "tcp-2", target = "tcp-two-first.example", protocol = "https"),
                strategyProbeResult(candidateId = "tcp-1", target = "tcp-one-first.example", protocol = "http"),
                strategyProbeResult(candidateId = "tcp-2", target = "tcp-two-second.example", protocol = "https"),
                strategyProbeResult(candidateId = "quic-1", target = "quic-one-first.example", protocol = "quic"),
                strategyProbeResult(candidateId = "tcp-1", target = "tcp-one-second.example", protocol = "http"),
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = reportResults, serviceMode = "VPN")

        assertEquals(listOf("tcp-1", "tcp-2", "quic-1", "quic-2"), uiModel.candidateDetails.keys.toList())
        assertEquals(
            listOf("tcp-one-first.example", "tcp-one-second.example"),
            requireNotNull(uiModel.candidateDetails["tcp-1"])
                .resultGroups
                .single { it.title == "HTTP results" }
                .items
                .map { it.target },
        )
        assertEquals(
            listOf("report-0-strategy_probe-tcp-two-first.example", "report-1-strategy_probe-tcp-two-second.example"),
            requireNotNull(uiModel.candidateDetails["tcp-2"])
                .resultGroups
                .single { it.title == "HTTPS results" }
                .items
                .map { it.id },
        )
        assertEquals(
            listOf("quic-one-first.example"),
            requireNotNull(uiModel.candidateDetails["quic-1"])
                .resultGroups
                .single { it.title == "QUIC results" }
                .items
                .map { it.target },
        )
    }

    @Test
    fun `quick suite report leaves winning path null`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteQuickV1,
                tcpWinnerId = "tcp-1",
                quicWinnerId = "quic-1",
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "VPN")

        assertNull(uiModel.winningPath)
    }

    @Test
    fun `dns short circuit report uses resolver headline and suppresses winning path`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpWinnerId = "tcp-1",
                quicWinnerId = "quic-1",
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
                skippedRecommendedCandidates = true,
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "VPN")

        assertEquals(StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED, uiModel.completionKind)
        assertEquals("Resolver override recommended", requireNotNull(uiModel.recommendation).headline)
        assertNull(uiModel.winningPath)
        assertEquals(
            "Fallback",
            requireNotNull(uiModel.candidateDetails["tcp-1"]).metrics.first { it.label == "Selected" }.value,
        )
    }

    @Test
    fun `winning path surfaces rooted emitter tier metadata`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpWinnerId = "tcp-2",
                quicWinnerId = "quic-2",
                tcpCandidates =
                    listOf(
                        strategyProbeCandidate(id = "tcp-1", label = "TCP baseline", family = "baseline_current"),
                        strategyProbeCandidate(
                            id = "tcp-2",
                            label = "TCP winner",
                            family = "seqovl",
                            emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                            exactEmitterRequiresRoot = true,
                        ),
                    ),
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "VPN")

        val winnerMetrics = requireNotNull(uiModel.winningPath).tcpWinner.metrics
        assertEquals("Rooted production", winnerMetrics.first { it.label == "Emitter" }.value)
        assertEquals("Rooted exact", winnerMetrics.first { it.label == "Realization" }.value)

        val candidateDetail = requireNotNull(uiModel.candidateDetails["tcp-2"])
        assertEquals("Rooted production", candidateDetail.metrics.first { it.label == "Emitter" }.value)
        assertEquals("Rooted exact", candidateDetail.metrics.first { it.label == "Realization" }.value)
        assertEquals(
            "Exact emission on this path required rooted runtime capabilities.",
            candidateDetail.notes.first(),
        )
    }

    @Test
    fun `candidate detail explains downgraded emitter fallback`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpWinnerId = "tcp-1",
                quicWinnerId = "quic-1",
                tcpCandidates =
                    listOf(
                        strategyProbeCandidate(
                            id = "tcp-1",
                            label = "TCP fallback winner",
                            family = "hostfake",
                            emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                            exactEmitterRequiresRoot = true,
                            emitterDowngraded = true,
                        ),
                        strategyProbeCandidate(id = "tcp-2", label = "TCP alternate", family = "split"),
                    ),
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "VPN")
        val candidateDetail = requireNotNull(uiModel.candidateDetails["tcp-1"])

        assertEquals("Approximate fallback", candidateDetail.metrics.first { it.label == "Realization" }.value)
        assertEquals(
            "Exact emission was unavailable, so this candidate ran through an approximate fallback path.",
            candidateDetail.notes.first(),
        )
    }

    @Test
    fun `recommendation surfaces proxy mode suppression notice`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteQuickV1,
                tcpWinnerId = "tcp-1",
                quicWinnerId = "quic-1",
            ).copy(
                recommendation =
                    requireNotNull(
                        strategyProbeReport(
                            suiteId = StrategyProbeSuiteQuickV1,
                            tcpWinnerId = "tcp-1",
                            quicWinnerId = "quic-1",
                        ).recommendation,
                    ).copy(
                        tlsPathSuppressed = true,
                        tlsPathSuppressionReason = "proxy_mode_browser_native_ech_suppressed",
                        tlsPathSuppressionSummary =
                            "Proxy mode leaves browser-originated TLS and ECH under the browser/OS stack; " +
                                "the selected ECH-aware template applies only to traffic the app originates itself.",
                    ),
            )

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "PROXY")

        assertEquals(
            "Proxy mode leaves browser-originated TLS and ECH under the browser/OS stack; " +
                "the selected ECH-aware template applies only to traffic the app originates itself.",
            requireNotNull(uiModel.recommendation)
                .fields
                .first { it.label == "Suppression" }
                .value,
        )
    }

    @Test
    fun `report without promotable candidate exposes no recommendation or winning path`() {
        val report =
            strategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpWinnerId = "tcp-1",
                quicWinnerId = "quic-1",
            ).copy(recommendation = null)

        val uiModel = support.toStrategyProbeReportUiModel(report, reportResults = emptyList(), serviceMode = "VPN")

        assertNull(uiModel.recommendation)
        assertNull(uiModel.winningPath)
        assertEquals(false, uiModel.families.flatMap { it.candidates }.any { it.recommended })
    }
}

private fun strategyProbeReport(
    suiteId: String,
    tcpWinnerId: String,
    quicWinnerId: String,
    completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
    skippedRecommendedCandidates: Boolean = false,
    tcpCandidates: List<StrategyProbeCandidateSummary>? = null,
    quicCandidates: List<StrategyProbeCandidateSummary>? = null,
): StrategyProbeReport =
    StrategyProbeReport(
        suiteId = suiteId,
        tcpCandidates =
            tcpCandidates
                ?: listOf(
                    strategyProbeCandidate(
                        id = "tcp-1",
                        label = "TCP baseline",
                        family = "baseline_current",
                        skipped = skippedRecommendedCandidates,
                        outcome = if (skippedRecommendedCandidates) "skipped" else "success",
                    ),
                    strategyProbeCandidate(id = "tcp-2", label = "TCP winner", family = "hostfake"),
                ),
        quicCandidates =
            quicCandidates
                ?: listOf(
                    strategyProbeCandidate(
                        id = "quic-1",
                        label = "QUIC baseline",
                        family = "quic_disabled",
                        skipped = skippedRecommendedCandidates,
                        outcome = if (skippedRecommendedCandidates) "skipped" else "success",
                    ),
                    strategyProbeCandidate(id = "quic-2", label = "QUIC winner", family = "quic_realistic_burst"),
                ),
        recommendation =
            StrategyProbeRecommendation(
                tcpCandidateId = tcpWinnerId,
                tcpCandidateLabel = "TCP winner",
                quicCandidateId = quicWinnerId,
                quicCandidateLabel = "QUIC winner",
                dnsStrategyLabel = "System DNS",
                rationale = "Best combined recovery across lanes.",
                recommendedProxyConfigJson = """{"kind":"ui"}""",
            ),
        completionKind = completionKind,
    )

private fun strategyProbeCandidate(
    id: String,
    label: String,
    family: String,
    skipped: Boolean = false,
    outcome: String = "success",
    emitterTier: StrategyEmitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
    exactEmitterRequiresRoot: Boolean = false,
    emitterDowngraded: Boolean = false,
): StrategyProbeCandidateSummary =
    StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        emitterTier = emitterTier,
        exactEmitterRequiresRoot = exactEmitterRequiresRoot,
        emitterDowngraded = emitterDowngraded,
        outcome = outcome,
        rationale = "Recovered target set.",
        succeededTargets = 1,
        totalTargets = 1,
        weightedSuccessScore = 10,
        totalWeight = 10,
        qualityScore = 10,
        skipped = skipped,
    )

private fun strategyProbeResult(
    candidateId: String,
    target: String,
    protocol: String,
): ProbeResult =
    ProbeResult(
        probeType = "strategy_probe",
        target = target,
        outcome = "success",
        details =
            listOf(
                ProbeDetail(key = "candidateId", value = candidateId),
                ProbeDetail(key = "protocol", value = protocol),
            ),
    )
