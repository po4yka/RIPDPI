package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ExecutionPlanSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.isExternalEmitter

internal val editionArchiveCapabilities = setOf("root_helper_available")

internal fun StrategyProbeCandidateSummary.editionArchiveRecommendationNote(lane: String): String? =
    "recommended_${lane}_rooted_emitter".takeIf { emitterTier.isExternalEmitter }

internal fun StrategyProbeCandidateSummary.editionArchiveEvidence(): String =
    ";exactEmitterRequiresRoot=$requiresExternalEmitter"

internal const val detectabilityBudgetThreshold = "recommended winner stays non-root and non-downgraded"

internal fun DiagnosticsArchiveDetectabilityMetrics.editionDetectabilityBudgetActual(): String =
    "rooted=$recommendedUsesExternalEmitter;downgraded=$recommendedWasDowngraded"

internal fun ExecutionPlanSnapshot.projectEditionPlanForArchive(): ExecutionPlanSnapshot = this

internal fun StrategyProbeCandidateSummary.hasEditionDetectabilityEvidence(): Boolean = true
