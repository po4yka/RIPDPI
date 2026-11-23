package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ExecutionPlanSnapshot
import com.poyka.ripdpi.diagnostics.StrategyCandidatePlanSnapshot
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary

internal val editionArchiveCapabilities = emptySet<String>()

@Suppress("UNUSED_PARAMETER")
internal fun StrategyProbeCandidateSummary.editionArchiveRecommendationNote(lane: String): String? = null

internal fun StrategyProbeCandidateSummary.editionArchiveEvidence(): String = ""

internal const val detectabilityBudgetThreshold = "recommended winner uses the standard emitter without approximation"

internal fun DiagnosticsArchiveDetectabilityMetrics.editionDetectabilityBudgetActual(): String =
    "downgraded=$recommendedWasDowngraded"

internal fun ExecutionPlanSnapshot.projectEditionPlanForArchive(): ExecutionPlanSnapshot =
    copy(
        strategy =
            strategy?.let { plan ->
                plan.copy(
                    tcpCandidates = plan.tcpCandidates.map { it.projectEditionCandidate() },
                    quicCandidates = plan.quicCandidates.map { it.projectEditionCandidate() },
                )
            },
    )

private fun StrategyCandidatePlanSnapshot.projectEditionCandidate(): StrategyCandidatePlanSnapshot =
    if (emitterTier == StrategyEmitterTier.UNAVAILABLE) {
        copy(label = "Unavailable candidate", eligibility = "unavailable", requiredCapabilities = emptyList())
    } else {
        copy(requiredCapabilities = requiredCapabilities.filter { it in archiveCapabilityTokens })
    }

internal fun StrategyProbeCandidateSummary.hasEditionDetectabilityEvidence(): Boolean = emitterTier.isProductionEmitter
