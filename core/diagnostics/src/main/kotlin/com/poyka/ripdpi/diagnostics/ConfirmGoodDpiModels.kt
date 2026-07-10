package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class ConfirmGoodDpiEvidenceSource {
    ACTIVE,
    PASSIVE,
    MIXED,
}

@Serializable
data class ConfirmGoodDpiEvidence(
    val source: ConfirmGoodDpiEvidenceSource,
    val stalledFlowCount: Int,
    val distinctTargetCount: Int,
    val catalogProfileValidated: Boolean,
    val realityHandshakeConfirmed: Boolean,
    val applicationResponseBytes: Long,
    val quicControlSucceeded: Boolean = false,
)

@Serializable
enum class ConfirmGoodDpiVerdictStatus {
    SUSPECTED,
}

@Serializable
data class ConfirmGoodDpiVerdict(
    val status: ConfirmGoodDpiVerdictStatus,
    val evidence: ConfirmGoodDpiEvidence,
)

@Serializable
enum class TransportFamily {
    UDP_QUIC,
}

@Serializable
enum class TransportPivotViability {
    CONFIRMED,
    UNKNOWN,
    BLOCKED,
}

@Serializable
data class TransportPivotRecommendation(
    val reasonCode: String,
    val preferredFamily: TransportFamily,
    val viability: TransportPivotViability,
    val selectedRelayRole: String? = null,
)
