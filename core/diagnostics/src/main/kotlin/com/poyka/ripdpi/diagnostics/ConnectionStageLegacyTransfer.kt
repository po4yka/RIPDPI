package com.poyka.ripdpi.diagnostics

internal fun legacyTransferConnectionLanes(evidence: ConnectionStageEvidence): List<ConnectionStageLane> {
    val count = evidence["statusReadings"]?.split('|')?.size ?: 0
    val configuredRuns = evidence["runs"]?.toIntOrNull() ?: 0
    val readings =
        if (configuredRuns in 1..MaxStageAttempts && count in 1..configuredRuns) {
            (TransferReadingKeys + listOf("durationMsReadings", "readCompletionReadings"))
                .map { evidence.readings(it, count) }
        } else {
            emptyList()
        }
    return if (readings.isEmpty() || readings.any { it == null }) {
        listOf(connectionLane(ConnectionLaneKind.TRANSFER, emptyList()))
    } else {
        (0 until count).map { index ->
            val values = readings.map { requireNotNull(it)[index] }
            val body =
                ConnectionStageMeasurement(
                    ConnectionStage.BODY,
                    legacyBodyState(values.last()),
                    elapsedMs = values[LegacyElapsedIndex].connectionStageNumber(MaxStageMilliseconds),
                )
            connectionLane(
                ConnectionLaneKind.TRANSFER,
                transferSetupStages(values, usesTls = evidence.transferUsesTls()) + body,
                attempt = index + 1,
            )
        }
    }
}

private fun legacyBodyState(token: String): ConnectionStageState =
    when (token) {
        "window_complete", "clean_eof_before_window" -> ConnectionStageState.PARTIAL
        "not_started" -> ConnectionStageState.NOT_REACHED
        "read_timeout" -> ConnectionStageState.TIMED_OUT
        "read_error" -> ConnectionStageState.FAILED
        else -> ConnectionStageState.UNKNOWN
    }

private const val LegacyElapsedIndex = 4
