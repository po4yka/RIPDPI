package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveCapabilitiesPayload(
    val capabilitiesVersion: String = "capabilities_v1",
    val sessionId: String?,
    val profileId: String?,
    val capabilities: List<DiagnosticsArchiveCapabilityEvidence>,
)

@Serializable
internal data class DiagnosticsArchiveCapabilityEvidence(
    val id: String,
    val status: DiagnosticsArchiveCapabilityStatus,
    val requiredBy: List<DiagnosticsArchiveCapabilityRequirement>,
    val evidenceRefs: List<String>,
)

@Serializable
internal data class DiagnosticsArchiveCapabilityRequirement(
    val lane: StrategyProbeProgressLane,
    val candidateId: String,
    val emitterTier: StrategyEmitterTier,
    val exactEmitterRequiresRoot: Boolean,
)

@Serializable
internal enum class DiagnosticsArchiveCapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    CONFLICTING,
    UNKNOWN,
}
