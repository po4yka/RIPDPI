package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveRelayTraceCompleteness(
    val retainedEventCount: Int = 0,
    val droppedEventCount: Long = 0,
    val retainedDecisionCount: Int = 0,
)

@Serializable
internal data class DiagnosticsArchiveRelayAttemptTraceRecord(
    val connectionCorrelation: String,
    val runtimeCorrelation: String,
    val attemptId: Long,
    val sequence: Long,
    val stage: String,
    val outcome: String,
    val durationMs: Long? = null,
    val failureStage: String? = null,
    val failureClass: String? = null,
    val ioErrorKind: String? = null,
    val osErrorCode: Int? = null,
    val peerClosePhase: String? = null,
    val carrierDisposition: String? = null,
    val causalInference: String = "not_established",
)

@Serializable
internal data class DiagnosticsArchiveRelayHealthDecisionRecord(
    val attemptId: String,
    val opaqueProfileId: String,
    val transport: String,
    val failureStage: String,
    val targetCategory: String,
    val positiveEvidenceWatermark: Long? = null,
    val decision: String,
    val cooldownScope: String,
    val cleanupReceipt: String,
    val connectionCorrelation: String,
    val runtimeCorrelation: String,
    val causalInference: String = "not_established",
)
