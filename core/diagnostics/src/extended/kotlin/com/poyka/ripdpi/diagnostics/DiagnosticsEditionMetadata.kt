package com.poyka.ripdpi.diagnostics

internal fun StrategyProbeCandidateSummary.editionRecommendationMetadata(lane: String): String? =
    "strategyRecommended${lane}RequiresRoot=true".takeIf { requiresExternalEmitter }
