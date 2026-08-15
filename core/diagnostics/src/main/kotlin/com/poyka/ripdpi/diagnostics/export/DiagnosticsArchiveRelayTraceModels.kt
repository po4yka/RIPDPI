package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveRelayTraceCompleteness(
    val retainedEventCount: Int = 0,
    val droppedEventCount: Long = 0,
    val retainedDecisionCount: Int = 0,
    val unsupportedAttemptCount: Int = 0,
    val sequenceGaps: List<DiagnosticsArchiveRelaySequenceGap> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveRelaySequenceGap(
    val attemptRef: String,
    val from: Long,
    val to: Long,
    val reason: String,
)

@Serializable
internal data class DiagnosticsArchiveRelayAttemptTraceRecord(
    val connectionCorrelation: String,
    val runtimeCorrelation: String,
    val attemptRef: String,
    val sequence: Long,
    val stage: String,
    val outcome: String,
    val durationMs: Long? = null,
    val failureStage: String? = null,
    val failureClass: String? = null,
    val ioErrorKind: String? = null,
    val osErrorCode: Int? = null,
    val peerClosePhase: String? = null,
    val carrierDisposition: String? = null,
    val causalInference: String = "not_established",
)

internal data class DiagnosticsArchiveRelayAttemptKey(
    val connectionSessionId: String,
    val runtimeId: String,
    val attemptId: Long,
)

internal fun selectRelayAttemptTraceEvents(
    primaryEvents: List<NativeSessionEventEntity>,
    globalEvents: List<NativeSessionEventEntity>,
): List<NativeSessionEventEntity> =
    (primaryEvents + globalEvents)
        .distinctBy { it.id }
        .filter { event ->
            !event.connectionSessionId.isNullOrBlank() &&
                !event.runtimeId.isNullOrBlank() &&
                event.attemptId != null &&
                event.attemptSequence != null &&
                event.stage in RelayTraceStages &&
                event.outcome in RelayTraceOutcomes
        }.sortedWith(
            compareBy(
                { it.connectionSessionId.orEmpty() },
                { it.runtimeId.orEmpty() },
                { it.attemptId },
                { it.attemptSequence },
            ),
        )

internal fun relayAttemptAliases(
    events: List<NativeSessionEventEntity>,
): Map<DiagnosticsArchiveRelayAttemptKey, String> =
    events
        .map(NativeSessionEventEntity::relayAttemptKey)
        .distinct()
        .mapIndexed { index, key -> key to "attempt-${index + 1}" }
        .toMap()

internal fun countUnsupportedRelayAttempts(
    allEvents: List<NativeSessionEventEntity>,
    retainedEvents: List<NativeSessionEventEntity>,
): Int {
    val retainedAttempts = retainedEvents.map(NativeSessionEventEntity::relayAttemptKey).toSet()
    return allEvents
        .distinctBy { it.id }
        .filter { event ->
            !event.connectionSessionId.isNullOrBlank() &&
                !event.runtimeId.isNullOrBlank() &&
                event.attemptId != null &&
                event.attemptSequence != null
        }.map(NativeSessionEventEntity::relayAttemptKey)
        .distinct()
        .count { it !in retainedAttempts }
}

internal fun buildRelaySequenceGaps(
    retainedEvents: List<NativeSessionEventEntity>,
    allEvents: List<NativeSessionEventEntity>,
    droppedEventsByConnection: Map<String, Long>,
): List<DiagnosticsArchiveRelaySequenceGap> {
    val aliases = relayAttemptAliases(retainedEvents)
    val observedSequences =
        allEvents
            .distinctBy { it.id }
            .filter { event ->
                !event.connectionSessionId.isNullOrBlank() &&
                    !event.runtimeId.isNullOrBlank() &&
                    event.attemptId != null &&
                    event.attemptSequence != null
            }.groupBy(NativeSessionEventEntity::relayAttemptKey)
            .mapValues { (_, events) -> events.mapNotNull { it.attemptSequence }.toSet() }
    return retainedEvents.groupBy(NativeSessionEventEntity::relayAttemptKey).flatMap { (attempt, attemptEvents) ->
        val sequences = attemptEvents.mapNotNull { it.attemptSequence }.distinct().sorted()
        val ranges =
            buildList {
                sequences.firstOrNull()?.takeIf { it > 1 }?.let { add(1L to it - 1) }
                sequences.zipWithNext().forEach { (left, right) ->
                    if (right > left + 1) add(left + 1 to right - 1)
                }
            }
        val fallbackReason =
            if ((droppedEventsByConnection[attempt.connectionSessionId] ?: 0) > 0) {
                "source_reported_connection_drop"
            } else {
                "retention_or_source_gap"
            }
        ranges.flatMap { (from, to) ->
            buildRelayGapSegments(
                attemptRef = requireNotNull(aliases[attempt]),
                from = from,
                to = to,
                unsupportedSequences = observedSequences[attempt].orEmpty(),
                fallbackReason = fallbackReason,
            )
        }
    }
}

private fun buildRelayGapSegments(
    attemptRef: String,
    from: Long,
    to: Long,
    unsupportedSequences: Set<Long>,
    fallbackReason: String,
): List<DiagnosticsArchiveRelaySequenceGap> =
    buildList {
        var cursor = from
        unsupportedSequences.filter { it in from..to }.sorted().forEach { sequence ->
            if (cursor < sequence) {
                add(DiagnosticsArchiveRelaySequenceGap(attemptRef, cursor, sequence - 1, fallbackReason))
            }
            add(DiagnosticsArchiveRelaySequenceGap(attemptRef, sequence, sequence, "unsupported_event"))
            cursor = sequence + 1
        }
        if (cursor <= to) add(DiagnosticsArchiveRelaySequenceGap(attemptRef, cursor, to, fallbackReason))
    }

internal fun NativeSessionEventEntity.relayAttemptKey(): DiagnosticsArchiveRelayAttemptKey =
    DiagnosticsArchiveRelayAttemptKey(
        connectionSessionId = connectionSessionId.orEmpty(),
        runtimeId = runtimeId.orEmpty(),
        attemptId = requireNotNull(attemptId),
    )

internal val RelayTraceStages =
    setOf(
        "tcp_connect",
        "reality_tls",
        "vless_auth",
        "vless_request",
        "vless_response",
        "socks_reply",
        "relay_stream",
    )
internal val RelayTraceOutcomes = setOf("started", "succeeded", "failed", "cancelled", "closed")

@Serializable
internal data class DiagnosticsArchiveRelayHealthDecisionRecord(
    val attemptId: String,
    val opaqueProfileId: String,
    val transport: String,
    val failureStage: String,
    val targetCategory: String,
    val positiveEvidenceWatermark: Long? = null,
    val decision: String,
    val cooldownScope: String,
    val cleanupReceipt: String,
    val connectionCorrelation: String,
    val runtimeCorrelation: String,
    val causalInference: String = "not_established",
)
