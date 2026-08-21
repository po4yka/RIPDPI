package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import kotlinx.serialization.Serializable

@Serializable
enum class HomeDetectionSignalCategory {
    LOCAL_INVENTORY_REVIEW,
    LOCAL_OBSERVER_EXPOSURE,
    NETWORK_OBSERVATION,
}

@Serializable
enum class HomeDetectionSignalSemantics {
    LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW,
    LOCAL_OBSERVER_EXPOSURE_PRESENT,
    NETWORK_OBSERVATION_PRESENT,
}

@Serializable
data class HomeDetectionDecisionSignal(
    val category: HomeDetectionSignalCategory,
    val semantics: HomeDetectionSignalSemantics,
    val source: String,
    val confidence: String,
    val scope: String,
)

internal fun HomeDetectionDecisionSignal.isContractValid(): Boolean {
    val parsedSource = runCatching { EvidenceSource.valueOf(source) }.getOrNull()
    val parsedConfidence = runCatching { EvidenceConfidence.valueOf(confidence) }.getOrNull()
    val parsedScope = runCatching { DetectionScope.valueOf(scope) }.getOrNull()
    val categoryMatches =
        when (category) {
            HomeDetectionSignalCategory.LOCAL_INVENTORY_REVIEW -> {
                semantics == HomeDetectionSignalSemantics.LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW &&
                    parsedScope == DetectionScope.LOCAL_INVENTORY
            }

            HomeDetectionSignalCategory.LOCAL_OBSERVER_EXPOSURE -> {
                semantics == HomeDetectionSignalSemantics.LOCAL_OBSERVER_EXPOSURE_PRESENT &&
                    parsedScope == DetectionScope.LOCAL_OBSERVER_EXPOSURE
            }

            HomeDetectionSignalCategory.NETWORK_OBSERVATION -> {
                semantics == HomeDetectionSignalSemantics.NETWORK_OBSERVATION_PRESENT &&
                    parsedScope == DetectionScope.NETWORK_OBSERVATION
            }
        }
    return parsedSource != null && parsedConfidence != null && categoryMatches
}

data class HomeDetectionStageOutcome(
    val verdict: DiagnosticsHomeDetectionVerdict,
    val detectedSignalCount: Int,
    val findings: List<String>,
    val ruleApplied: String? = null,
    val evidenceScopes: List<DetectionScope> = emptyList(),
    val localFindings: List<String> = emptyList(),
    val networkFindings: List<String> = emptyList(),
    val decisionSignals: List<HomeDetectionDecisionSignal> = emptyList(),
) {
    init {
        require(detectedSignalCount == decisionSignals.size)
        require(decisionSignals.all(HomeDetectionDecisionSignal::isContractValid))
        require(
            evidenceScopes ==
                decisionSignals
                    .map { DetectionScope.valueOf(it.scope) }
                    .distinct(),
        )
    }
}

interface HomeDetectionStageRunner {
    suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome?
}

object NoopHomeDetectionStageRunner : HomeDetectionStageRunner {
    override suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome? =
        null
}
