package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ObservationKind
import com.poyka.ripdpi.diagnostics.TransportFailureKind
import kotlinx.serialization.Serializable

@Serializable
internal data class ProtocolMilestoneArchiveRecord(
    val milestoneVersion: String = "protocol_milestone_v1",
    val sessionId: String?,
    val profileId: String?,
    val sequence: Int,
    val observationIndex: Int,
    val subjectAlias: String,
    val observationKind: ObservationKind,
    val protocol: ProtocolMilestoneArchiveProtocol,
    val milestone: ProtocolMilestoneArchiveKind,
    val status: ProtocolMilestoneArchiveStatus,
    val outcome: String,
    val failureKind: TransportFailureKind? = null,
    val evidenceRef: String,
)

@Serializable
internal enum class ProtocolMilestoneArchiveProtocol {
    DNS,
    TCP,
    HTTP,
    TLS_1_2,
    TLS_1_3,
    TLS_ECH,
    QUIC,
    ENDPOINT,
}

@Serializable
internal enum class ProtocolMilestoneArchiveKind {
    DNS_COMPARISON,
    TCP_CONNECT,
    TCP_FLOW,
    HTTP_RESPONSE,
    HTTP_BOOTSTRAP,
    HTTP_MEDIA_RESPONSE,
    TLS_HANDSHAKE,
    TLS_ECH_NEGOTIATION,
    QUIC_RESPONSE,
    SERVICE_ENDPOINT,
    CIRCUMVENTION_HANDSHAKE,
}

@Serializable
internal enum class ProtocolMilestoneArchiveStatus {
    REACHED,
    FAILED,
    OBSERVED,
}
