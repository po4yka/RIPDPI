package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiHostAutolearnConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsHomeRecommendationApplierTest {
    @Test
    fun `apply recommendation preserves current host autolearn controls`() =
        runTest {
            val appSettingsRepository =
                FakeAppSettingsRepository(
                    defaultDiagnosticsAppSettings()
                        .toBuilder()
                        .setHostAutolearnEnabled(true)
                        .setHostAutolearnPenaltyTtlHours(12)
                        .setHostAutolearnMaxHosts(2048)
                        .build(),
                )
            val sanitizedConfig =
                RipDpiProxyUIPreferences(
                    hostAutolearn =
                        RipDpiHostAutolearnConfig(
                            enabled = false,
                            penaltyTtlHours = 6,
                            maxHosts = 512,
                        ),
                ).toNativeConfigJson()

            DiagnosticsHomeRecommendationApplier(appSettingsRepository)
                .applyValidatedRecommendation(eligibleStrategyProbeReport(sanitizedConfig))

            val savedSettings = appSettingsRepository.snapshot()
            assertEquals(
                Triple(true, 12, 2048),
                Triple(
                    savedSettings.hostAutolearnEnabled,
                    savedSettings.hostAutolearnPenaltyTtlHours,
                    savedSettings.hostAutolearnMaxHosts,
                ),
            )
        }

    private fun eligibleStrategyProbeReport(recommendedProxyConfigJson: String): StrategyProbeReport =
        StrategyProbeReport(
            suiteId = "full_matrix_v1",
            tcpCandidates =
                listOf(
                    StrategyProbeCandidateSummary(
                        id = "tcp-split",
                        label = "TCP split",
                        family = "split",
                        outcome = "ok",
                        rationale = "TCP path worked",
                        succeededTargets = 3,
                        totalTargets = 4,
                        weightedSuccessScore = 75,
                        totalWeight = 100,
                        qualityScore = 80,
                        domainOutcomes =
                            listOf(
                                StrategyProbeDomainOutcome(
                                    domain = "control.example",
                                    succeeded = true,
                                    isControl = true,
                                ),
                            ),
                    ),
                ),
            quicCandidates =
                listOf(
                    StrategyProbeCandidateSummary(
                        id = "quic-fake",
                        label = "QUIC fake",
                        family = "fake",
                        outcome = "ok",
                        rationale = "QUIC path worked",
                        succeededTargets = 2,
                        totalTargets = 3,
                        weightedSuccessScore = 66,
                        totalWeight = 100,
                        qualityScore = 70,
                    ),
                ),
            recommendation =
                StrategyProbeRecommendation(
                    tcpCandidateId = "tcp-split",
                    tcpCandidateLabel = "TCP split",
                    quicCandidateId = "quic-fake",
                    quicCandidateLabel = "QUIC fake",
                    rationale = "Best combined result",
                    recommendedProxyConfigJson = recommendedProxyConfigJson,
                ),
            auditAssessment =
                StrategyProbeAuditAssessment(
                    coverage =
                        StrategyProbeAuditCoverage(
                            tcpCandidatesPlanned = 1,
                            tcpCandidatesExecuted = 1,
                            tcpCandidatesSkipped = 0,
                            tcpCandidatesNotApplicable = 0,
                            quicCandidatesPlanned = 1,
                            quicCandidatesExecuted = 1,
                            quicCandidatesSkipped = 0,
                            quicCandidatesNotApplicable = 0,
                            tcpWinnerSucceededTargets = 3,
                            tcpWinnerTotalTargets = 4,
                            quicWinnerSucceededTargets = 2,
                            quicWinnerTotalTargets = 3,
                            matrixCoveragePercent = 90,
                            winnerCoveragePercent = 80,
                            tcpWinnerCoveragePercent = 75,
                            quicWinnerCoveragePercent = 66,
                        ),
                    confidence =
                        StrategyProbeAuditConfidence(
                            level = StrategyProbeAuditConfidenceLevel.HIGH,
                            score = 92,
                            rationale = "Sufficient evidence",
                        ),
                ),
            pilotBucketLabels = listOf("control:neutral:success"),
        )
}
