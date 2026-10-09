package com.poyka.ripdpi.diagnostics

internal fun transferConnectionLanes(
    evidence: ConnectionStageEvidence,
    transfer: TransferEvidence?,
): List<ConnectionStageLane> {
    val runs = transfer?.runs
    if (runs == null) {
        return if (evidence.contains("transferEvidence")) {
            listOf(connectionLane(ConnectionLaneKind.TRANSFER, emptyList()))
        } else {
            legacyTransferConnectionLanes(evidence)
        }
    }
    val readings = TransferReadingKeys.map { evidence.readings(it, runs.size) }
    val aligned = readings.all { it != null }
    return runs.mapIndexed { index, measurement ->
        val setup =
            if (aligned) {
                transferSetupStages(
                    readings.map {
                        requireNotNull(it)[index]
                    },
                    measurement.terminationReason,
                    evidence.transferUsesTls(),
                )
            } else {
                emptyList()
            }
        transferConnectionLane(measurement, setup, evidence["transferNetworkScope"] == "unverified")
    }
}

internal fun transferConnectionLane(
    measurement: TransferMeasurement,
    setup: List<ConnectionStageMeasurement> = emptyList(),
    unverified: Boolean = false,
): ConnectionStageLane {
    if (measurement.receivedBodyByteCount !in 0..MaxStageBytes || measurement.elapsedMs !in 0..MaxStageMilliseconds) {
        return connectionLane(
            ConnectionLaneKind.TRANSFER,
            emptyList(),
            attempt = measurement.runIndex,
            unverified = unverified,
        )
    }
    val received = measurement.receivedBodyByteCount.takeIf { it <= MaxStageBytes }
    val elapsed = measurement.elapsedMs.takeIf { it <= MaxStageMilliseconds }
    val first = measurement.firstBodyByteMs?.takeIf { it <= MaxStageMilliseconds }
    val headersObserved =
        setup.any {
            it.stage == ConnectionStage.HTTP_HEADERS && it.state == ConnectionStageState.OBSERVED
        }
    val bodyState =
        if (measurement.receivedBodyByteCount == 0L && setup.any { it.state in InterruptedSetupStates }) {
            ConnectionStageState.NOT_REACHED
        } else if (measurement.receivedBodyByteCount == 0L &&
            measurement.terminationReason in UnlocatedTransferStates &&
            !headersObserved
        ) {
            ConnectionStageState.UNKNOWN
        } else {
            transferBodyState(measurement)
        }
    val observedHeaders =
        if ((received != null && received > 0) || measurement.responseComplete) {
            listOf(ConnectionStageMeasurement(ConnectionStage.HTTP_HEADERS, ConnectionStageState.OBSERVED))
        } else {
            emptyList()
        }
    val headers = setup.filter { it.stage == ConnectionStage.HTTP_HEADERS }.ifEmpty { observedHeaders }
    return connectionLane(
        ConnectionLaneKind.TRANSFER,
        setup.filterNot { it.stage == ConnectionStage.HTTP_HEADERS } + headers +
            listOf(
                ConnectionStageMeasurement(
                    ConnectionStage.FIRST_BODY_BYTE,
                    if (received != null &&
                        received > 0
                    ) {
                        ConnectionStageState.OBSERVED
                    } else {
                        ConnectionStageState.UNKNOWN
                    },
                    elapsedMs = first,
                ),
                ConnectionStageMeasurement(ConnectionStage.BODY, bodyState, elapsedMs = elapsed, byteCount = received),
            ),
        attempt = measurement.runIndex,
        unverified = unverified,
    )
}

private fun transferBodyState(measurement: TransferMeasurement): ConnectionStageState =
    when (measurement.terminationReason) {
        null -> ConnectionStageState.RUNNING
        "content_length_complete", "chunked_complete", "eof_complete" -> ConnectionStageState.SUCCEEDED
        "window_limit", "early_eof" -> ConnectionStageState.PARTIAL
        "cancelled" -> ConnectionStageState.CANCELLED
        "deadline", "idle_timeout" -> ConnectionStageState.TIMED_OUT
        "setup_error", "http_error" -> ConnectionStageState.NOT_REACHED
        "reset", "read_error", "invalid_framing" -> ConnectionStageState.FAILED
        else -> ConnectionStageState.UNKNOWN
    }

internal fun transferSetupStages(
    readings: List<String>,
    termination: String? = null,
    usesTls: Boolean? = null,
): List<ConnectionStageMeasurement> {
    val failedStage = failureStage(readings[0])
    val tcpDuration = readings[1].connectionStageNumber(MaxStageMilliseconds)
    val tlsDuration = readings[2].connectionStageNumber(MaxStageMilliseconds)
    val code = readings[TransferHttpCodeIndex].toIntOrNull()?.takeIf { it in HttpStatusRange }
    val tlsMeasured = tlsDuration?.let { it > 0 || usesTls == true } == true
    val failureToExpose =
        failedStage?.takeUnless {
            it == ConnectionStage.BODY || (it == ConnectionStage.HTTP_HEADERS && code != null)
        }
    return buildList {
        if (tcpDuration != null && failedStage != ConnectionStage.TCP) {
            add(ConnectionStageMeasurement(ConnectionStage.TCP, ConnectionStageState.SUCCEEDED))
        }
        if (usesTls == false) {
            add(ConnectionStageMeasurement(ConnectionStage.TLS, ConnectionStageState.NOT_APPLICABLE))
        } else if (tlsMeasured && failedStage != ConnectionStage.TLS) {
            add(
                ConnectionStageMeasurement(
                    ConnectionStage.TLS,
                    ConnectionStageState.SUCCEEDED,
                    durationMs = tlsDuration,
                ),
            )
        }
        if (code != null) {
            add(
                ConnectionStageMeasurement(
                    ConnectionStage.HTTP_HEADERS,
                    ConnectionStageState.OBSERVED,
                    httpStatusCode = code,
                ),
            )
        }
        if (failureToExpose != null) {
            add(
                ConnectionStageMeasurement(
                    failureToExpose,
                    when (termination) {
                        "cancelled" -> ConnectionStageState.CANCELLED
                        "deadline", "idle_timeout" -> ConnectionStageState.TIMED_OUT
                        else -> ConnectionStageState.FAILED
                    },
                ),
            )
        }
    }
}

internal val TransferReadingKeys =
    listOf(
        "failureStageReadings",
        "tcpConnectMsReadings",
        "tlsHandshakeMsReadings",
        "httpStatusCodeReadings",
    )

private val InterruptedSetupStates =
    setOf(
        ConnectionStageState.FAILED,
        ConnectionStageState.CANCELLED,
        ConnectionStageState.TIMED_OUT,
    )

internal fun ConnectionStageEvidence.transferUsesTls(): Boolean? =
    when {
        this["url"]?.startsWith("https://", ignoreCase = true) == true -> true
        this["url"]?.startsWith("http://", ignoreCase = true) == true -> false
        else -> null
    }

private val UnlocatedTransferStates = setOf(null, "cancelled", "deadline", "idle_timeout")

private const val TransferHttpCodeIndex = 3
