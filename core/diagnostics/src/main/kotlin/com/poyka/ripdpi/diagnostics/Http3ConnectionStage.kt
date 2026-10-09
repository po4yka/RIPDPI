package com.poyka.ripdpi.diagnostics

internal fun http3ConnectionLane(details: List<ProbeDetail>): ConnectionStageLane {
    val evidence = parseHttp3ProbeEvidence(details)
    return connectionLane(
        ConnectionLaneKind.HTTP3,
        evidence?.http3Measurements().orEmpty(),
        applicable = ConnectionHttp3Stages.toSet() - setOf(ConnectionStage.TCP, ConnectionStage.PROXY),
        unverified = evidence?.reason == "network_scope_unverified",
    )
}

private fun Http3ProbeEvidence.http3Measurements(): List<ConnectionStageMeasurement> =
    listOf(
        ConnectionStageMeasurement(ConnectionStage.DNS, dnsState()),
        ConnectionStageMeasurement(
            ConnectionStage.QUIC_HANDSHAKE,
            measuredState(tlsValidated, Http3ProbeStage.QUIC_HANDSHAKE),
            elapsedMs = handshakeElapsedMs,
        ),
        ConnectionStageMeasurement(
            ConnectionStage.TLS,
            measuredState(tlsValidated, Http3ProbeStage.QUIC_HANDSHAKE),
            elapsedMs = handshakeElapsedMs,
        ),
        ConnectionStageMeasurement(
            ConnectionStage.HTTP_HEADERS,
            measuredState(http3Validated, Http3ProbeStage.HTTP_HEADERS),
            elapsedMs = headersElapsedMs,
            httpStatusCode = httpStatus,
        ),
        ConnectionStageMeasurement(
            ConnectionStage.FIRST_BODY_BYTE,
            if (bodyComplete && responseBytes == 0L) {
                ConnectionStageState.NOT_APPLICABLE
            } else {
                measuredState(responseBytes > 0L, Http3ProbeStage.HTTP_BODY)
            },
            elapsedMs = firstByteElapsedMs,
        ),
        ConnectionStageMeasurement(
            ConnectionStage.BODY,
            measuredState(bodyComplete, Http3ProbeStage.HTTP_BODY),
            elapsedMs = durationMs?.takeIf { stage == Http3ProbeStage.HTTP_BODY },
            byteCount = responseBytes.takeIf { http3Validated },
        ),
    )

private fun Http3ProbeEvidence.dnsState(): ConnectionStageState =
    when (dnsStatus) {
        Http3DnsStatus.PINNED -> ConnectionStageState.NOT_APPLICABLE
        Http3DnsStatus.RESOLVED -> ConnectionStageState.SUCCEEDED
        Http3DnsStatus.FAILED -> ConnectionStageState.FAILED
        Http3DnsStatus.TIMEOUT -> ConnectionStageState.TIMED_OUT
        Http3DnsStatus.NOT_RUN -> ConnectionStageState.NOT_REACHED
    }

private fun Http3ProbeEvidence.measuredState(
    complete: Boolean,
    target: Http3ProbeStage,
): ConnectionStageState =
    when {
        complete -> ConnectionStageState.SUCCEEDED
        status == Http3ProbeStatus.UNSUPPORTED -> ConnectionStageState.NOT_APPLICABLE
        status == Http3ProbeStatus.NOT_OBSERVED || status == Http3ProbeStatus.INVALID -> ConnectionStageState.UNKNOWN
        stage.ordinal < target.ordinal -> ConnectionStageState.NOT_REACHED
        stage.ordinal > target.ordinal -> ConnectionStageState.UNKNOWN
        else -> stopState()
    }

private fun Http3ProbeEvidence.stopState(): ConnectionStageState =
    when (status) {
        Http3ProbeStatus.TIMEOUT -> ConnectionStageState.TIMED_OUT
        Http3ProbeStatus.CANCELLED -> ConnectionStageState.CANCELLED
        Http3ProbeStatus.FAILED, Http3ProbeStatus.HTTP_ERROR -> ConnectionStageState.FAILED
        Http3ProbeStatus.BODY_LIMIT -> ConnectionStageState.PARTIAL
        else -> ConnectionStageState.UNKNOWN
    }
