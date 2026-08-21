package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val PostRuntimeRestoreContextKind = "post_runtime_restore"

@Serializable
internal data class DiagnosticsArchivePostRuntimeRestoreEvidence(
    val rawWindowGeneration: Long,
    val resumeIntentGeneration: Long,
    val executionOutcome: String,
    val settlementOutcome: String,
    val runtimeWasRunning: Boolean,
    val resumeRequired: Boolean,
    val postRuntimeStatus: String? = null,
    val postRuntimeMode: String? = null,
    val restoreErrorPresent: Boolean,
    val executionFailurePresent: Boolean,
)

internal data class DiagnosticsArchivePostRuntimeRestoreSelection(
    val evidence: DiagnosticsArchivePostRuntimeRestoreEvidence? = null,
    val malformedCount: Int = 0,
)

internal fun DiagnosticContextEntity.isPostRuntimeRestoreContext(): Boolean =
    contextKind.equals(PostRuntimeRestoreContextKind, ignoreCase = true)

@Serializable
internal data class ArchiveRawPathExecutionResultWire(
    val settlement: ArchiveRawPathExecutionSettlementWire,
    @SerialName("execution_outcome")
    val executionOutcome: String,
    @SerialName("execution_failure")
    val executionFailure: String? = null,
)

@Serializable
internal data class ArchiveRawPathExecutionSettlementWire(
    @SerialName("raw_window_generation")
    val rawWindowGeneration: Long,
    @SerialName("resume_intent_generation")
    val resumeIntentGeneration: Long,
    val outcome: String,
    @SerialName("runtime_was_running")
    val runtimeWasRunning: Boolean,
    @SerialName("resume_required")
    val resumeRequired: Boolean,
    @SerialName("post_runtime_context")
    val postRuntimeContext: ArchiveRawPathRuntimeContextWire,
    @SerialName("restore_failure")
    val restoreFailure: String? = null,
)

@Serializable
internal data class ArchiveRawPathRuntimeContextWire(
    val status: String? = null,
    val mode: String? = null,
)

internal fun ArchiveRawPathExecutionResultWire.toArchiveEvidence(): DiagnosticsArchivePostRuntimeRestoreEvidence? {
    val hasValidContract =
        executionOutcome in RawPathExecutionOutcomes &&
            settlement.outcome in RawPathSettlementOutcomes &&
            settlement.postRuntimeContext.status.isNullOrAllowed(RawPathRuntimeStatuses) &&
            settlement.postRuntimeContext.mode.isNullOrAllowed(RawPathRuntimeModes)
    return takeIf { hasValidContract }?.let {
        DiagnosticsArchivePostRuntimeRestoreEvidence(
            rawWindowGeneration = settlement.rawWindowGeneration,
            resumeIntentGeneration = settlement.resumeIntentGeneration,
            executionOutcome = executionOutcome,
            settlementOutcome = settlement.outcome,
            runtimeWasRunning = settlement.runtimeWasRunning,
            resumeRequired = settlement.resumeRequired,
            postRuntimeStatus = settlement.postRuntimeContext.status,
            postRuntimeMode = settlement.postRuntimeContext.mode,
            restoreErrorPresent = !settlement.restoreFailure.isNullOrBlank(),
            executionFailurePresent = !executionFailure.isNullOrBlank(),
        )
    }
}

private fun String?.isNullOrAllowed(allowedValues: Set<String>): Boolean = this == null || this in allowedValues

private val RawPathExecutionOutcomes =
    setOf("completed", "block_failed", "block_cancelled", "entry_failed", "entry_cancelled")
private val RawPathSettlementOutcomes =
    setOf(
        "restored",
        "restore_not_required",
        "restore_failed",
        "restore_policy_disabled",
        "superseded_by_user_stop",
    )
private val RawPathRuntimeStatuses = setOf("halted", "reconnecting", "running")
private val RawPathRuntimeModes = setOf("proxy", "vpn")
