package com.poyka.ripdpi.diagnostics

/** Read one value only. Duplicate keys are ambiguous even when their values match. */
internal class ConnectionStageEvidence(
    details: List<ProbeDetail>,
) {
    private val fields = details.groupBy(ProbeDetail::key)

    operator fun get(key: String): String? = fields[key]?.singleOrNull()?.value?.takeIf { it.length <= MaxValueLength }

    fun contains(key: String): Boolean = fields.containsKey(key)

    fun duration(key: String): Long? = this[key]?.connectionStageNumber(MaxStageMilliseconds)

    fun bytes(key: String): Long? = this[key]?.connectionStageNumber(MaxStageBytes)

    fun status(key: String): Int? = this[key]?.toIntOrNull()?.takeIf { it in HttpStatusRange }

    fun flag(key: String): Boolean? = this[key]?.toBooleanStrictOrNull()

    fun readings(
        key: String,
        count: Int,
    ): List<String>? = this[key]?.split('|')?.takeIf { it.size == count && count in 1..MaxStageAttempts }

    companion object {
        private const val MaxValueLength = 65_536
    }
}

internal fun String.connectionStageNumber(maximum: Long): Long? =
    takeIf { isNotEmpty() && all(Char::isDigit) }?.toLongOrNull()?.takeIf { it in 0..maximum }

internal const val MaxStageMilliseconds = 86_400_000L
internal const val MaxStageBytes = 1_099_511_627_776L
internal const val MaxStageAttempts = 10
internal val HttpStatusRange = 100..599
internal val ConnectionStandardStages =
    listOf(
        ConnectionStage.DNS,
        ConnectionStage.TCP,
        ConnectionStage.PROXY,
        ConnectionStage.TLS,
        ConnectionStage.HTTP_HEADERS,
        ConnectionStage.FIRST_BODY_BYTE,
        ConnectionStage.BODY,
    )

internal fun connectionLane(
    kind: ConnectionLaneKind,
    measurements: List<ConnectionStageMeasurement>,
    applicable: Set<ConnectionStage> = ConnectionStandardStages.toSet(),
    attempt: Int? = null,
    unverified: Boolean = false,
): ConnectionStageLane =
    ConnectionStageLane(
        kind = kind,
        attempt = attempt,
        stages =
            (if (kind == ConnectionLaneKind.QUIC) listOf(ConnectionStage.QUIC_RESPONSE) else ConnectionStandardStages)
                .map { stage ->
                    measurements.singleOrNull { it.stage == stage }
                        ?: ConnectionStageMeasurement(
                            stage,
                            if (stage in
                                applicable
                            ) {
                                ConnectionStageState.UNKNOWN
                            } else {
                                ConnectionStageState.NOT_APPLICABLE
                            },
                        )
                },
        networkScopeUnverified = unverified,
    )

internal fun stageState(token: String?): ConnectionStageState =
    when (token) {
        "ok", "success", "connected", "complete" -> ConnectionStageState.SUCCEEDED
        "failed", "error", "reset", "blocked" -> ConnectionStageState.FAILED
        "timeout", "timed_out", "deadline" -> ConnectionStageState.TIMED_OUT
        "cancelled" -> ConnectionStageState.CANCELLED
        "not_attempted", "not_reached" -> ConnectionStageState.NOT_REACHED
        "not_applicable", "skipped", "pinned" -> ConnectionStageState.NOT_APPLICABLE
        "partial", "limit" -> ConnectionStageState.PARTIAL
        else -> ConnectionStageState.UNKNOWN
    }
