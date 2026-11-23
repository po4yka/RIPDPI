package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class DiagnosticsArchiveDetectabilityMetrics(
    @Transient
    val externalProductionCandidates: Int = 0,
    val labOnlyCandidates: Int = 0,
    val downgradedCandidates: Int = 0,
    @Transient
    val externalEmitterRequiredCandidates: Int = 0,
    val capabilitySkippedCandidates: Int = 0,
    val skippedCandidates: Int = 0,
    @Transient
    val recommendedUsesExternalEmitter: Boolean = false,
    val recommendedWasDowngraded: Boolean = false,
    val evidence: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)
