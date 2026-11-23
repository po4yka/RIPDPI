package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveDetectabilityMetrics(
    @SerialName("rootedProductionCandidates")
    val externalProductionCandidates: Int = 0,
    val labOnlyCandidates: Int = 0,
    val downgradedCandidates: Int = 0,
    @SerialName("exactRootRequiredCandidates")
    val externalEmitterRequiredCandidates: Int = 0,
    val capabilitySkippedCandidates: Int = 0,
    val skippedCandidates: Int = 0,
    @SerialName("recommendedUsesRootedEmitter")
    val recommendedUsesExternalEmitter: Boolean = false,
    val recommendedWasDowngraded: Boolean = false,
    val evidence: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)
