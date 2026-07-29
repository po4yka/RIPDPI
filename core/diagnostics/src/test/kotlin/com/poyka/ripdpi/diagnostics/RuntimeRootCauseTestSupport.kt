package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity

internal fun assessRootCause(
    connectionSessionId: String,
    events: List<NativeSessionEventEntity>,
    terminalAtMillis: Long = events.maxOfOrNull(NativeSessionEventEntity::createdAt) ?: 0L,
    terminalEvidenceSealed: Boolean = true,
): RuntimeRootCauseAssessment =
    RuntimeRootCauseClassifier.assess(
        connectionSessionId = connectionSessionId,
        events = events,
        terminalAtMillis = terminalAtMillis,
        terminalEvidenceSealed = terminalEvidenceSealed,
    )

internal fun rootCauseEvent(
    connectionSessionId: String?,
    subsystem: String,
    message: String,
    createdAt: Long,
    source: String = "service",
    level: String = "info",
    id: String = "${connectionSessionId.orEmpty()}:$subsystem:$createdAt",
    preserveMessage: Boolean = false,
): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = source,
        level = level,
        message =
            if (!preserveMessage && subsystem == "data_plane" && "final=true" in message) {
                "$message event_kind=data_plane_final"
            } else {
                message
            },
        createdAt = createdAt,
        subsystem = subsystem,
    )

internal fun typedDnsRuntimeEvent(
    connectionSessionId: String,
    state: String,
    createdAt: Long,
    id: String = "typed_runtime_state:dns:$connectionSessionId",
    source: String = "service_telemetry_state",
    subsystem: String = "dns",
    message: String = "event=dns_runtime_state evidence=dns_counter_transition_v1 state=$state",
): NativeSessionEventEntity =
    rootCauseEvent(
        connectionSessionId = connectionSessionId,
        subsystem = subsystem,
        source = source,
        level = "warn",
        message = message,
        createdAt = createdAt,
        id = id,
        preserveMessage = true,
    )

internal fun typedProcessExitCorrelationEvent(
    connectionSessionId: String,
    createdAt: Long,
    reason: String = "other",
    subtype: String = "android_memory_limiter",
    importance: String = "service",
    id: String = "application_exit_correlation:$connectionSessionId",
    source: String = "application_exit_correlation",
    subsystem: String = "process",
    message: String =
        "event=process_exit_correlation verdict=inconclusive evidence=last_exit_inspector_v1 " +
            "reason=$reason subtype=$subtype importance=$importance",
): NativeSessionEventEntity =
    rootCauseEvent(
        connectionSessionId = connectionSessionId,
        subsystem = subsystem,
        source = source,
        level = "warn",
        message = message,
        createdAt = createdAt,
        id = id,
        preserveMessage = true,
    )

internal fun typedRelayRuntimeEvent(
    connectionSessionId: String,
    relayFailed: Boolean,
    createdAt: Long,
): NativeSessionEventEntity =
    rootCauseEvent(
        connectionSessionId = connectionSessionId,
        subsystem = "relay",
        source = "service_telemetry_state",
        level = "warn",
        message =
            "event=relay_runtime_state evidence=relay_health_transition_v1 " +
                "state=failed health=failed relay_failed=$relayFailed",
        createdAt = createdAt,
        id = "typed_runtime_state:relay:$connectionSessionId",
        preserveMessage = true,
    )
