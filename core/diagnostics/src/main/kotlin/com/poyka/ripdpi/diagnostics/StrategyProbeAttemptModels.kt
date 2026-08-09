package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class StrategyProbeAttemptStatus {
    PLANNED,
    EXECUTED,
    SKIPPED,
    NOT_APPLICABLE,
    ELIMINATED,
    TIMED_OUT,
}

@Serializable
enum class StrategyProbeAttemptRound {
    BASELINE,
    QUALIFIER,
    FULL_MATRIX,
    NOT_STARTED,
}

@Serializable
data class StrategyProbeAttempt(
    val attemptVersion: String,
    val sequence: Int,
    val candidateIndex: Int,
    val candidateId: String,
    val candidateLabel: String,
    val candidateFamily: String,
    val lane: StrategyProbeProgressLane,
    val target: String,
    val targetIndex: Int,
    val isControl: Boolean,
    val protocol: String,
    val round: StrategyProbeAttemptRound,
    val status: StrategyProbeAttemptStatus,
    val startedAtMs: Long? = null,
    val durationMs: Long? = null,
    val retryCount: Int = 0,
    val outcome: String? = null,
    val reason: String? = null,
)
