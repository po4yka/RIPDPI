package com.poyka.ripdpi.diagnostics

internal fun dnsConnectionLanes(evidence: ConnectionStageEvidence): List<ConnectionStageLane> {
    val attempts = evidence["udpAttemptCount"]?.connectionStageNumber(MaxDnsAttempts)
    val successes = evidence["udpSuccessCount"]?.connectionStageNumber(MaxDnsAttempts)
    val systemState =
        when {
            attempts != null && successes != null && successes in 1..attempts -> ConnectionStageState.SUCCEEDED

            successes == 0L && evidence["udpErrorKind"] in
                setOf(
                    "timeout",
                    "would_block",
                )
            -> ConnectionStageState.TIMED_OUT

            successes == 0L && evidence["udpErrorKind"] in DnsErrorKinds -> ConnectionStageState.FAILED

            else -> ConnectionStageState.UNKNOWN
        }
    val encryptedAnswers = evidence["encryptedAnswerSetSize"]?.connectionStageNumber(MaxDnsAnswers)
    val encryptedState =
        if (encryptedAnswers != null && encryptedAnswers > 0) {
            ConnectionStageState.SUCCEEDED
        } else {
            ConnectionStageState.UNKNOWN
        }
    return listOf(
        dnsConnectionLane(ConnectionLaneKind.DNS_SYSTEM, systemState, evidence.duration("udpLatencyMs")),
        dnsConnectionLane(ConnectionLaneKind.DNS_ENCRYPTED, encryptedState, evidence.duration("encryptedLatencyMs")),
    )
}

private fun dnsConnectionLane(
    kind: ConnectionLaneKind,
    state: ConnectionStageState,
    duration: Long?,
): ConnectionStageLane =
    connectionLane(
        kind,
        listOf(ConnectionStageMeasurement(ConnectionStage.DNS, state, durationMs = duration)),
        applicable = setOf(ConnectionStage.DNS),
    )

internal fun tcpConnectionLane(evidence: ConnectionStageEvidence): ConnectionStageLane {
    val duration = evidence.duration("synAckLatencyMs")
    val sent = evidence.bytes("bytesSent")
    val state =
        when {
            duration != null -> ConnectionStageState.SUCCEEDED
            sent != null && sent > 0 -> ConnectionStageState.OBSERVED
            evidence["tcpBlockMethod"] == "timeout" -> ConnectionStageState.TIMED_OUT
            else -> ConnectionStageState.UNKNOWN
        }
    return connectionLane(
        ConnectionLaneKind.TCP,
        listOf(ConnectionStageMeasurement(ConnectionStage.TCP, state)),
    )
}

private const val MaxDnsAttempts = 100L
private const val MaxDnsAnswers = 65_536L
private val DnsErrorKinds = setOf("refused", "unreachable", "nxdomain", "other")
