package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveEmissionReceipt(
    val receiptVersion: String = "emission_receipt_v1",
    val sessionId: String?,
    val profileId: String?,
    val sequence: Int,
    val lane: StrategyProbeProgressLane,
    val candidateId: String,
    val candidateFamily: String,
    val plannedEmitterTier: StrategyEmitterTier,
    val observedEmitterTier: StrategyEmitterTier?,
    val exactEmitterRequiresRoot: Boolean,
    val emitterDowngraded: Boolean?,
    val requiredCapabilities: List<String>,
    val emissionStatus: DiagnosticsArchiveEmissionStatus,
    val evidenceBasis: DiagnosticsArchiveEmissionEvidenceBasis,
    val executedAttemptCount: Int,
    val evidenceRefs: List<String>,
)

@Serializable
internal enum class DiagnosticsArchiveEmissionStatus {
    EXACT_EMITTER_EXECUTED,
    DOWNGRADED_EMITTER_EXECUTED,
    NOT_EMITTED,
    UNVERIFIED,
}

@Serializable
internal enum class DiagnosticsArchiveEmissionEvidenceBasis {
    ATTEMPT_EXECUTED,
    CAPABILITY_SKIPPED,
    CONFLICTING_EVIDENCE,
    ATTEMPT_NOT_EXECUTED,
    SUMMARY_ONLY,
    PLAN_ONLY,
}
