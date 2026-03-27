package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
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
}

private fun strategyProbeReport(
    suiteId: String,
    tcpWinnerId: String,
    quicWinnerId: String,
): StrategyProbeReport =
    StrategyProbeReport(
        suiteId = suiteId,
        tcpCandidates =
            listOf(
                strategyProbeCandidate(id = "tcp-1", label = "TCP baseline", family = "baseline_current"),
                strategyProbeCandidate(id = "tcp-2", label = "TCP winner", family = "hostfake"),
            ),
        quicCandidates =
            listOf(
                strategyProbeCandidate(id = "quic-1", label = "QUIC baseline", family = "quic_disabled"),
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
    )

private fun strategyProbeCandidate(
    id: String,
    label: String,
    family: String,
): StrategyProbeCandidateSummary =
    StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        outcome = "success",
        rationale = "Recovered target set.",
        succeededTargets = 1,
        totalTargets = 1,
        weightedSuccessScore = 10,
        totalWeight = 10,
        qualityScore = 10,
    )
