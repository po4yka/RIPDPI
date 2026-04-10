package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.PreferredEdgeCandidate

internal data class BypassCombinationCandidate(
    val id: String,
    val rememberedValidated: Boolean,
    val strategyConfidence: StrategyProbeAuditConfidenceLevel? = null,
    val winnerCoveragePercent: Int = 0,
    val combinedTransportSuccess: Int = 0,
    val dnsMatchCount: Int = 0,
    val dnsBootstrapValidatedCount: Int = 0,
    val freshEdgeSuccessScore: Int = 0,
    val echPreserving: Boolean = false,
    val continuityBonus: Int = 0,
    val resolverPath: EncryptedDnsPathCandidate? = null,
    val strategyRecommendation: StrategyProbeRecommendation? = null,
)

internal object BypassCombinationScorer {
    fun chooseBest(candidates: List<BypassCombinationCandidate>): BypassCombinationCandidate? =
        candidates.maxWithOrNull(candidateComparator)

    fun rememberedCandidate(
        resolverPath: EncryptedDnsPathCandidate?,
        strategyRecommendation: StrategyProbeRecommendation?,
    ): BypassCombinationCandidate =
        BypassCombinationCandidate(
            id = "remembered",
            rememberedValidated = true,
            resolverPath = resolverPath,
            strategyRecommendation = strategyRecommendation,
        )

    fun freshCandidate(
        report: ScanReport,
        resolverPath: EncryptedDnsPathCandidate?,
        currentDnsProtocol: String?,
        currentTcpFamily: String?,
        currentQuicFamily: String?,
        preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
    ): BypassCombinationCandidate {
        val strategyProbe = report.strategyProbeReport
        val assessment = strategyProbe?.auditAssessment
        val tcpWinner =
            strategyProbe?.tcpCandidates?.firstOrNull { it.id == strategyProbe.recommendation.tcpCandidateId }
        val quicWinner =
            strategyProbe?.quicCandidates?.firstOrNull { it.id == strategyProbe.recommendation.quicCandidateId }
        val continuityBonus =
            listOfNotNull(
                currentDnsProtocol?.takeIf { resolverPath?.protocol == it }?.let { 1 },
                currentTcpFamily?.takeIf { strategyProbe?.recommendation?.tcpCandidateFamily == it }?.let { 1 },
                currentQuicFamily?.takeIf { strategyProbe?.recommendation?.quicCandidateFamily == it }?.let { 1 },
            ).sum()
        return BypassCombinationCandidate(
            id = "fresh",
            rememberedValidated = false,
            strategyConfidence = assessment?.confidence?.level,
            winnerCoveragePercent = assessment?.coverage?.winnerCoveragePercent ?: 0,
            combinedTransportSuccess =
                (tcpWinner?.weightedSuccessScore ?: 0) + (quicWinner?.weightedSuccessScore ?: 0),
            dnsMatchCount = report.resolverRecommendation?.selectedBootstrapIps?.size ?: 0,
            dnsBootstrapValidatedCount = report.resolverRecommendation?.selectedBootstrapIps?.size ?: 0,
            freshEdgeSuccessScore = preferredEdges.values.flatten().sumOf { it.successCount - it.failureCount },
            echPreserving =
                report.results.any { result ->
                    result.details.any { detail ->
                        detail.key == "tlsEchResolutionDetail" && detail.value == "ech_config_available"
                    }
                },
            continuityBonus = continuityBonus,
            resolverPath = resolverPath,
            strategyRecommendation = strategyProbe?.recommendation,
        )
    }

    private val candidateComparator: Comparator<BypassCombinationCandidate> =
        compareBy<BypassCombinationCandidate>(
            { if (it.rememberedValidated) 1 else 0 },
            { confidenceRank(it.strategyConfidence) },
            { it.winnerCoveragePercent },
            { it.combinedTransportSuccess },
            { it.dnsMatchCount },
            { it.dnsBootstrapValidatedCount },
            { it.freshEdgeSuccessScore },
            { if (it.echPreserving) 1 else 0 },
            { it.continuityBonus },
        )

    private fun confidenceRank(level: StrategyProbeAuditConfidenceLevel?): Int =
        when (level) {
            StrategyProbeAuditConfidenceLevel.HIGH -> 3
            StrategyProbeAuditConfidenceLevel.MEDIUM -> 2
            StrategyProbeAuditConfidenceLevel.LOW -> 1
            null -> 0
        }
}
