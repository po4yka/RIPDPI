package com.poyka.ripdpi.diagnostics

internal fun tlsConnectionLanes(evidence: ConnectionStageEvidence): List<ConnectionStageLane> =
    TlsConnectionPrefixes.map { (prefix, kind) ->
        val status = evidence["${prefix}Status"]
        val failedStage = failureStage(evidence["${prefix}FailureStage"])?.takeIf { it in TlsOnlyStages }
        val tls =
            ConnectionStageMeasurement(
                ConnectionStage.TLS,
                when {
                    status == "tls_ok" && failedStage == null -> ConnectionStageState.SUCCEEDED
                    status == "not_run" -> ConnectionStageState.NOT_REACHED
                    failedStage != null && failedStage != ConnectionStage.TLS -> ConnectionStageState.NOT_REACHED
                    status == "tls_cert_invalid" -> ConnectionStageState.FAILED
                    else -> ConnectionStageState.UNKNOWN
                },
            )
        val failure =
            failedStage?.let { stage ->
                ConnectionStageMeasurement(
                    stage,
                    ConnectionStageState.FAILED,
                    elapsedMs = evidence.duration("${prefix}FailureDurationMs"),
                )
            }
        connectionLane(
            kind,
            if (failure == null) listOf(tls) else listOf(tls).filterNot { it.stage == failedStage } + failure,
            applicable = TlsOnlyStages,
        )
    }

internal fun serviceConnectionLanes(evidence: ConnectionStageEvidence): List<ConnectionStageLane> =
    listOf(
        httpConnectionLane(evidence, "bootstrapStatus", ConnectionLaneKind.BOOTSTRAP),
        httpConnectionLane(evidence, "mediaStatus", ConnectionLaneKind.MEDIA),
        endpointConnectionLane(evidence["gatewayStatus"], ConnectionLaneKind.GATEWAY),
        quicConnectionLane(evidence["quicStatus"]),
    )

internal fun circumventionConnectionLanes(evidence: ConnectionStageEvidence): List<ConnectionStageLane> =
    listOf(
        httpConnectionLane(evidence, "bootstrapStatus", ConnectionLaneKind.BOOTSTRAP),
        endpointConnectionLane(evidence["handshakeStatus"], ConnectionLaneKind.HANDSHAKE),
    )

private fun endpointConnectionLane(
    status: String?,
    kind: ConnectionLaneKind,
): ConnectionStageLane =
    connectionLane(
        kind,
        listOf(
            ConnectionStageMeasurement(
                ConnectionStage.TCP,
                when (status) {
                    "tcp_connect_ok" -> ConnectionStageState.SUCCEEDED
                    "not_run" -> ConnectionStageState.NOT_REACHED
                    else -> ConnectionStageState.UNKNOWN
                },
            ),
            ConnectionStageMeasurement(
                ConnectionStage.TLS,
                when (status) {
                    "tls_ok" -> ConnectionStageState.SUCCEEDED
                    "tcp_connect_ok", "tcp_connect_failed" -> ConnectionStageState.NOT_APPLICABLE
                    "not_run" -> ConnectionStageState.NOT_REACHED
                    "tls_cert_invalid" -> ConnectionStageState.FAILED
                    else -> ConnectionStageState.UNKNOWN
                },
            ),
        ),
        applicable = TlsOnlyStages,
    )

private val TlsConnectionPrefixes =
    listOf(
        "tls13" to ConnectionLaneKind.TLS13,
        "tls12" to ConnectionLaneKind.TLS12,
        "tlsEch" to ConnectionLaneKind.TLS_ECH,
    )
private val TlsOnlyStages = setOf(ConnectionStage.DNS, ConnectionStage.TCP, ConnectionStage.PROXY, ConnectionStage.TLS)
