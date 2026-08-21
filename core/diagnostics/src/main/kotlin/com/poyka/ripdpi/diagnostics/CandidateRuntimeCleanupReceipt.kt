package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CandidateRuntimeCleanupReceipt(
    val started: Int,
    val stopped: Int,
    val joined: Int,
    val forcedAbort: Int,
    val candidates: List<CandidateRuntimeCleanupDetail> = emptyList(),
    val duplicateRefusalCount: Int = 0,
    val addressAttemptCount: Int = 0,
    val connectionRefusedCount: Int = 0,
    val drainLatencyMs: Long = 0,
    val cleanupOutcome: CandidateRuntimeCleanupOutcome? = null,
)

@Serializable
data class CandidateRuntimeCleanupDetail(
    val scanSessionId: String,
    val candidateId: String,
    val lane: StrategyProbeProgressLane,
    val outcome: CandidateRuntimeCleanupOutcome,
    val addressAttemptCount: Int,
    val connectionRefusedCount: Int,
    val duplicateRefusalCount: Int,
    val drainLatencyMs: Long,
    val forcedAbort: Boolean,
)

@Serializable
enum class CandidateRuntimeCleanupOutcome {
    @SerialName("graceful")
    GRACEFUL,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("deadline")
    DEADLINE,

    @SerialName("aborted")
    ABORTED,

    @SerialName("join_failed")
    JOIN_FAILED,

    @SerialName("runtime_failed")
    RUNTIME_FAILED,
}
