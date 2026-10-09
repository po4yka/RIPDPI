package com.poyka.ripdpi.diagnostics

enum class ConnectionStage { DNS, TCP, PROXY, TLS, HTTP_HEADERS, FIRST_BODY_BYTE, BODY, QUIC_RESPONSE }

enum class ConnectionStageState {
    SUCCEEDED,
    OBSERVED,
    FAILED,
    PARTIAL,
    RUNNING,
    NOT_REACHED,
    NOT_APPLICABLE,
    UNKNOWN,
    CANCELLED,
    TIMED_OUT,
}

enum class ConnectionLaneKind {
    MATRIX,
    TRANSFER,
    TLS13,
    TLS12,
    TLS_ECH,
    HTTP,
    QUIC,
    DNS_SYSTEM,
    DNS_ENCRYPTED,
    BOOTSTRAP,
    MEDIA,
    GATEWAY,
    HANDSHAKE,
    TCP,
}

data class ConnectionStageMeasurement(
    val stage: ConnectionStage,
    val state: ConnectionStageState,
    val durationMs: Long? = null,
    val elapsedMs: Long? = null,
    val byteCount: Long? = null,
    val httpStatusCode: Int? = null,
)

data class ConnectionStageLane(
    val kind: ConnectionLaneKind,
    val attempt: Int? = null,
    val stages: List<ConnectionStageMeasurement>,
    val networkScopeUnverified: Boolean = false,
)

data class ConnectionStageScale(
    val lanes: List<ConnectionStageLane>,
)
