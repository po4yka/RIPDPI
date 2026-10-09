package com.poyka.ripdpi.diagnostics

internal fun httpConnectionLane(
    evidence: ConnectionStageEvidence,
    key: String,
    kind: ConnectionLaneKind = ConnectionLaneKind.HTTP,
    outcome: String? = null,
): ConnectionStageLane {
    val status = evidence[key]
    val code =
        (if (kind == ConnectionLaneKind.HTTP) evidence.status("httpStatusCode") else null)
            ?: httpCode(status) ?: httpCode(outcome)
    val state =
        when {
            code != null || status in
                setOf(
                    "http_ok",
                    "http_blockpage",
                ) || outcome == "http_ok" -> ConnectionStageState.OBSERVED

            status == "not_run" -> ConnectionStageState.NOT_REACHED

            status in HttpFailureStatuses -> ConnectionStageState.FAILED

            else -> ConnectionStageState.UNKNOWN
        }
    return connectionLane(
        kind,
        listOf(ConnectionStageMeasurement(ConnectionStage.HTTP_HEADERS, state, httpStatusCode = code)),
        applicable = if (kind == ConnectionLaneKind.HTTP) PlainHttpStages else ConnectionStandardStages.toSet(),
    )
}

internal fun httpCode(token: String?): Int? =
    token
        ?.removePrefix("http_status_")
        ?.takeIf { token.startsWith("http_status_") }
        ?.toIntOrNull()
        ?.takeIf { it in HttpStatusRange }

internal fun quicConnectionLane(
    status: String?,
    latency: Long? = null,
): ConnectionStageLane =
    connectionLane(
        ConnectionLaneKind.QUIC,
        listOf(
            ConnectionStageMeasurement(
                ConnectionStage.QUIC_RESPONSE,
                when (status) {
                    "quic_initial_response", "quic_response" -> ConnectionStageState.OBSERVED
                    "quic_timeout", "timeout" -> ConnectionStageState.TIMED_OUT
                    "quic_error", "quic_failed" -> ConnectionStageState.FAILED
                    "not_run" -> ConnectionStageState.NOT_REACHED
                    else -> ConnectionStageState.UNKNOWN
                },
                durationMs = latency,
            ),
        ),
    )

internal fun failureStage(token: String?): ConnectionStage? =
    when (token) {
        "dns", "dns_resolution" -> ConnectionStage.DNS
        "tcp", "tcp_connect" -> ConnectionStage.TCP
        "socks5_negotiation" -> ConnectionStage.PROXY
        "tls", "tls_handshake" -> ConnectionStage.TLS
        "http", "request_write", "response_headers", "response_status" -> ConnectionStage.HTTP_HEADERS
        "body", "body_read" -> ConnectionStage.BODY
        else -> null
    }

internal val PlainHttpStages = ConnectionStandardStages.filterNot { it == ConnectionStage.TLS }.toSet()
private val HttpFailureStatuses = setOf("http_error", "http_failed", "http_reset", "http_eof")
