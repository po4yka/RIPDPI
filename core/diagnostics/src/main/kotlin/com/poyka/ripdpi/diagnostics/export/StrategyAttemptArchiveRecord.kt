package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.StrategyProbeAttemptRound
import com.poyka.ripdpi.diagnostics.StrategyProbeAttemptStatus
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import kotlinx.serialization.Serializable

@Serializable
internal data class StrategyAttemptArchiveRecord(
    val attemptVersion: String,
    val sessionId: String?,
    val profileId: String?,
    val sequence: Int,
    val candidateIndex: Int,
    val candidateId: String,
    val candidateLabel: String,
    val candidateFamily: String,
    val lane: StrategyProbeProgressLane,
    val targetAlias: String,
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
