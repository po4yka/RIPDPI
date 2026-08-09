package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable

@Serializable
internal data class DecisionTraceArchivePayload(
    val traceVersion: String = "decision_trace_v1",
    val sessionId: String?,
    val profileId: String?,
    val decisions: List<DecisionTraceArchiveEntry>,
)

@Serializable
internal data class DecisionTraceArchiveEntry(
    val sequence: Int,
    val decisionType: String,
    val outcome: String,
    val reasonCode: String? = null,
    val rationale: String? = null,
    val selections: List<DecisionTraceArchiveSelection> = emptyList(),
    val confidence: DecisionTraceArchiveConfidence? = null,
    val evidenceRefs: List<String>,
)

@Serializable
internal data class DecisionTraceArchiveSelection(
    val dimension: String,
    val value: String,
)

@Serializable
internal data class DecisionTraceArchiveConfidence(
    val level: String,
    val score: Int? = null,
)
