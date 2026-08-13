package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
internal data class RuntimeTerminalArtifactBatch(
    val events: List<NativeSessionEventEntity>,
    val telemetrySample: TelemetrySampleEntity? = null,
)

internal fun buildPrivacySafeTerminalArtifactBatch(
    connectionSessionId: String,
    typedEvents: List<NativeSessionEventEntity>,
    nativeEvents: List<NativeRuntimeEvent>,
    telemetrySample: TelemetrySampleEntity?,
): RuntimeTerminalArtifactBatch =
    RuntimeTerminalArtifactBatch(
        events =
            (
                typedEvents.mapNotNull { event -> event.toPrivacySafeTypedEvent(connectionSessionId) } +
                    nativeEvents.mapIndexedNotNull { index, event ->
                        event
                            .toPrivacySafePersistedRuntimeEvent()
                            ?.toPrivacySafeTerminalEvent(connectionSessionId, index)
                    }
            ).distinctBy(NativeSessionEventEntity::id),
        telemetrySample = telemetrySample?.toPrivacySafeTerminalProjection(),
    )

private fun NativeSessionEventEntity.toPrivacySafeTypedEvent(connectionSessionId: String): NativeSessionEventEntity? {
    if (this.connectionSessionId != connectionSessionId || source != "service_telemetry_state") return null
    val tokens = message.toKeyValueTokens()
    val safeMessage =
        when (subsystem) {
            "dns" -> tokens.toPrivacySafeDnsMessage()
            "relay" -> tokens.toPrivacySafeRelayMessage()
            else -> null
        }
    return safeMessage?.let { message ->
        copy(
            sessionId = null,
            connectionSessionId = connectionSessionId,
            level = level.lowercase(Locale.US).takeIf { it in TerminalEventLevels } ?: "info",
            message = message,
            runtimeId = null,
            mode = null,
            policySignature = null,
            fingerprintHash = null,
        )
    }
}

private fun NativeRuntimeEvent.toPrivacySafeTerminalEvent(
    connectionSessionId: String,
    index: Int,
): NativeSessionEventEntity? {
    val tokens = message.toKeyValueTokens()
    return when (kind) {
        "data_plane_final" -> toPrivacySafeDataPlaneEvent(connectionSessionId, tokens)
        "protect_failure" -> toPrivacySafeProtectEvent(connectionSessionId, index, tokens)
        "runtime_ready", "runtime_stopped" -> toPrivacySafeRelayLifecycleEvent(connectionSessionId, index)
        else -> null
    }
}

private fun NativeRuntimeEvent.toPrivacySafeRelayLifecycleEvent(
    connectionSessionId: String,
    index: Int,
): NativeSessionEventEntity? =
    kind
        ?.takeIf { source == "relay" && subsystem == "relay" }
        ?.let { lifecycleKind ->
            terminalEvent(
                connectionSessionId = connectionSessionId,
                id = "runtime_terminal_event:$connectionSessionId:$index",
                source = "relay",
                level = "info",
                message = "event=relay_$lifecycleKind",
                createdAt = createdAt,
                subsystem = "relay",
            )
        }

private fun Map<String, String>.toPrivacySafeDnsMessage(): String? {
    val state = get("state")?.takeIf { it in DnsRuntimeStates }
    val valid = get("event") == "dns_runtime_state" && get("evidence") == "dns_counter_transition_v1"
    return state?.takeIf { valid }?.let { "event=dns_runtime_state evidence=dns_counter_transition_v1 state=$it" }
}

private fun Map<String, String>.toPrivacySafeRelayMessage(): String? {
    val state = get("state")?.takeIf { it in RelayRuntimeStates }
    val health = get("health")?.takeIf { it in RelayRuntimeHealthValues }
    val relayFailed = get("relay_failed")?.takeIf { it == "true" || it == "false" }
    val valid = get("event") == "relay_runtime_state" && get("evidence") == "relay_health_transition_v1"
    val complete = listOf(valid, state != null, health != null, relayFailed != null).all { it }
    return if (complete) {
        "event=relay_runtime_state evidence=relay_health_transition_v1 " +
            "state=$state health=$health relay_failed=$relayFailed"
    } else {
        null
    }
}

private fun NativeRuntimeEvent.toPrivacySafeDataPlaneEvent(
    connectionSessionId: String,
    tokens: Map<String, String>,
): NativeSessionEventEntity? {
    val state = tokens["state"]?.takeIf { it in DataPlaneCorrelationStates }
    val mode = tokens["mode"]?.takeIf { it in DataPlaneModes }
    val generation = tokens["generation"]?.toLongOrNull()?.takeIf { it >= 0L }
    val complete = listOf(state != null, mode != null, generation != null, tokens["final"] == "true").all { it }
    return if (complete) {
        terminalEvent(
            connectionSessionId = connectionSessionId,
            id = terminalDataPlaneEventId(connectionSessionId),
            level = "info",
            message = "state=$state mode=$mode generation=$generation final=true event_kind=data_plane_final",
            createdAt = createdAt,
            subsystem = "data_plane",
        )
    } else {
        null
    }
}

private fun NativeRuntimeEvent.toPrivacySafeProtectEvent(
    connectionSessionId: String,
    index: Int,
    tokens: Map<String, String>,
): NativeSessionEventEntity? =
    tokens["event"]
        ?.takeIf { it == "protect_failed" }
        ?.let {
            terminalEvent(
                connectionSessionId = connectionSessionId,
                id = "runtime_terminal_event:$connectionSessionId:$index",
                level = "warn",
                message = "event=protect_failed event_kind=protect_failure",
                createdAt = createdAt,
                subsystem = "protect",
            )
        }

private fun terminalEvent(
    connectionSessionId: String,
    id: String,
    source: String = "service",
    level: String,
    message: String,
    createdAt: Long,
    subsystem: String,
): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = source,
        level = level,
        message = message,
        createdAt = createdAt,
        subsystem = subsystem,
    )

internal fun terminalDataPlaneEventId(connectionSessionId: String): String =
    "runtime_terminal_event:$connectionSessionId:data_plane_final"

private fun TelemetrySampleEntity.toPrivacySafeTerminalProjection(): TelemetrySampleEntity =
    copy(
        sessionId = null,
        activeMode = activeMode?.lowercase(Locale.US)?.takeIf { it in ServiceModes } ?: "unknown",
        connectionState = "Stopped",
        networkType = networkType.lowercase(Locale.US).takeIf { it in NetworkTypes } ?: "unknown",
        publicIp = null,
        telemetryNetworkFingerprintHash = null,
        winningTcpStrategyFamily = null,
        winningQuicStrategyFamily = null,
        resolverId = null,
        resolverProtocol = resolverProtocol?.lowercase(Locale.US)?.takeIf { it in ResolverProtocols },
        resolverEndpoint = null,
        resolverFallbackReason = null,
        networkHandoverClass = null,
        networkHandoverState = null,
        proxyTelemetryMessage = null,
        relayTelemetryMessage = null,
        warpTelemetryMessage = null,
        tunnelTelemetryMessage = null,
        lastFailureClass = null,
        lastFallbackAction = null,
        relayProtocolKind = null,
    )

private val TerminalEventLevels = setOf("info", "warn", "error")
private val DnsRuntimeStates = setOf("failure_threshold", "recovered")
private val RelayRuntimeStates =
    setOf("idle", "starting", "running", "stopping", "stopped", "degraded", "failed", "error", "unknown")
private val RelayRuntimeHealthValues = setOf("idle", "ok", "healthy", "degraded", "failed", "error", "unknown")
private val DataPlaneModes = setOf("vpn", "proxy")
private val DataPlaneCorrelationStates =
    setOf(
        "evidence_unavailable",
        "evidence_unavailable_partial",
        "no_flow",
        "tun_ingress_no_upstream",
        "upstream_open_no_payload",
        "outbound_only",
        "tun_return_without_proxy_outbound",
        "proxy_outbound_observed",
        "cross_layer_return_observed",
    )
private val ServiceModes = setOf("vpn", "proxy")
private val NetworkTypes = setOf("wifi", "cellular", "ethernet", "vpn", "unknown")
private val ResolverProtocols = setOf("doh", "dot", "udp", "tcp", "system")
