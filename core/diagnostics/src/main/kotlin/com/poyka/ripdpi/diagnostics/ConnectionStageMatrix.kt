package com.poyka.ripdpi.diagnostics

internal fun matrixConnectionLane(
    evidence: ConnectionStageEvidence,
    outcome: String,
): ConnectionStageLane {
    val statusCode = evidence.status("httpStatusCode")
    val byteCount = evidence.bytes("bodyByteCount")
    val failure = failureStage(evidence["failureStage"])
    val measurements =
        MatrixStageKeys.map { (stage, key) ->
            val state =
                when {
                    stage == ConnectionStage.DNS && evidence[key] == "admitted_addresses" -> {
                        ConnectionStageState.NOT_APPLICABLE
                    }

                    stage == ConnectionStage.HTTP_HEADERS && statusCode != null -> {
                        ConnectionStageState.OBSERVED
                    }

                    failure == stage && outcome == "matrix_target_cancelled" -> {
                        ConnectionStageState.CANCELLED
                    }

                    stage == ConnectionStage.BODY -> {
                        matrixBodyState(evidence, byteCount)
                    }

                    else -> {
                        matrixStageState(evidence[key])
                    }
                }
            ConnectionStageMeasurement(
                stage,
                state,
                durationMs =
                    if (stage == ConnectionStage.DNS && state == ConnectionStageState.SUCCEEDED) {
                        evidence.duration("dnsElapsedMs")
                    } else {
                        null
                    },
                byteCount = byteCount.takeIf { stage == ConnectionStage.BODY },
                httpStatusCode = statusCode.takeIf { stage == ConnectionStage.HTTP_HEADERS },
            )
        } +
            ConnectionStageMeasurement(
                ConnectionStage.FIRST_BODY_BYTE,
                if (byteCount != null && byteCount > 0) ConnectionStageState.OBSERVED else ConnectionStageState.UNKNOWN,
            )
    return connectionLane(
        ConnectionLaneKind.MATRIX,
        measurements,
        applicable = ConnectionStandardStages.toSet(),
        attempt = evidence["attempt"]?.toIntOrNull()?.takeIf { it in 1..MatrixStageMaxAttempts },
        unverified = evidence["networkScope"] == "unverified",
    )
}

private fun matrixBodyState(
    evidence: ConnectionStageEvidence,
    byteCount: Long?,
): ConnectionStageState {
    val status = evidence["bodyStatus"]
    val complete = evidence.flag("bodyComplete")
    val state = matrixStageState(status)
    return when {
        status == "failed" && evidence["bodyReason"] in setOf("response_limit", "early_eof") -> {
            if (complete == false && byteCount != null) ConnectionStageState.PARTIAL else ConnectionStageState.UNKNOWN
        }

        state == ConnectionStageState.SUCCEEDED -> {
            if (complete == true && byteCount != null) ConnectionStageState.SUCCEEDED else ConnectionStageState.UNKNOWN
        }

        else -> {
            state
        }
    }
}

private fun matrixStageState(token: String?): ConnectionStageState =
    when (token) {
        "not_run" -> ConnectionStageState.NOT_REACHED
        "body_limit", "size_limit", "limit_reached", "incomplete", "early_eof" -> ConnectionStageState.PARTIAL
        else -> stageState(token)
    }

private val MatrixStageKeys =
    listOf(
        ConnectionStage.DNS to "dnsStatus",
        ConnectionStage.TCP to "tcpStatus",
        ConnectionStage.TLS to "tlsStatus",
        ConnectionStage.HTTP_HEADERS to "httpStatus",
        ConnectionStage.BODY to "bodyStatus",
    )

private const val MatrixStageMaxAttempts = 3
