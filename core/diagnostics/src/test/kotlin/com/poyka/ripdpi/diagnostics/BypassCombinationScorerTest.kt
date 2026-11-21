package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class BypassCombinationScorerTest {
    @Test
    fun `remembered validated combination beats fresh low confidence result`() {
        val winner =
            requireNotNull(
                BypassCombinationScorer.chooseBest(
                    listOf(
                        BypassCombinationCandidate(
                            id = "fresh",
                            rememberedValidated = false,
                            strategyConfidence = StrategyProbeAuditConfidenceLevel.LOW,
                            winnerCoveragePercent = 100,
                            combinedTransportSuccess = 200,
                            dnsMatchCount = 3,
                            dnsBootstrapValidatedCount = 3,
                            freshEdgeSuccessScore = 5,
                            echPreserving = true,
                            continuityBonus = 2,
                        ),
                        BypassCombinationScorer.rememberedCandidate(
                            resolverPath = null,
                            strategyRecommendation = null,
                        ),
                    ),
                ),
            )

        assertEquals("remembered", winner.id)
    }

    @Test
    fun `fresh persistable result beats remembered validated combination`() {
        val winner =
            requireNotNull(
                BypassCombinationScorer.chooseBest(
                    listOf(
                        BypassCombinationScorer.rememberedCandidate(
                            resolverPath = null,
                            strategyRecommendation = null,
                        ),
                        candidate(id = "fresh").copy(persistenceEligible = true),
                    ),
                ),
            )

        assertEquals("fresh", winner.id)
    }

    @Test
    fun `remembered validated combination beats fresh high confidence result that cannot be persisted`() {
        val winner =
            requireNotNull(
                BypassCombinationScorer.chooseBest(
                    listOf(
                        candidate(id = "fresh"),
                        BypassCombinationScorer.rememberedCandidate(
                            resolverPath = null,
                            strategyRecommendation = null,
                        ),
                    ),
                ),
            )

        assertEquals("remembered", winner.id)
    }

    @Test
    fun `ech preserving combination beats non ech combination when transport scores tie`() {
        val winner =
            requireNotNull(
                BypassCombinationScorer.chooseBest(
                    listOf(
                        candidate(id = "non-ech", echPreserving = false),
                        candidate(id = "ech", echPreserving = true),
                    ),
                ),
            )

        assertEquals("ech", winner.id)
    }

    @Test
    fun `stronger dns evidence beats weaker resolver when transport scores tie`() {
        val winner =
            requireNotNull(
                BypassCombinationScorer.chooseBest(
                    listOf(
                        candidate(
                            id = "weak-dns",
                            dnsMatchCount = 1,
                            dnsBootstrapValidatedCount = 1,
                        ),
                        candidate(
                            id = "strong-dns",
                            dnsMatchCount = 3,
                            dnsBootstrapValidatedCount = 2,
                        ),
                    ),
                ),
            )

        assertEquals("strong-dns", winner.id)
    }

    private fun candidate(
        id: String,
        dnsMatchCount: Int = 2,
        dnsBootstrapValidatedCount: Int = 1,
        echPreserving: Boolean = false,
    ) = BypassCombinationCandidate(
        id = id,
        rememberedValidated = false,
        strategyConfidence = StrategyProbeAuditConfidenceLevel.HIGH,
        winnerCoveragePercent = 80,
        combinedTransportSuccess = 160,
        dnsMatchCount = dnsMatchCount,
        dnsBootstrapValidatedCount = dnsBootstrapValidatedCount,
        freshEdgeSuccessScore = 2,
        echPreserving = echPreserving,
        continuityBonus = 1,
    )
}
