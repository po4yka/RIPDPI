package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import java.util.Locale

internal class TypedRuntimeHealthState {
    private var dnsBaseline: DnsCounterSnapshot? = null
    private var dnsFailureStreak = 0
    private var dnsFailureActive = false
    private var relayFailureActive = false

    fun acceptDnsCounters(
        counters: DnsCounterSnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity? {
        val baseline = dnsBaseline
        if (baseline == null) {
            dnsBaseline = counters
            dnsFailureStreak = 0
            dnsFailureActive = false
            return null
        }
        if (counters.hasDifferentProducerThan(baseline)) {
            val wasFailureActive = dnsFailureActive
            dnsBaseline = counters
            dnsFailureStreak = 0
            return recoverDns(connectionSessionId, createdAt).takeIf { wasFailureActive }
        }
        if (counters.hasRollbackFrom(baseline)) {
            val wasFailureActive = dnsFailureActive
            dnsBaseline = counters
            dnsFailureStreak = 0
            return recoverDns(connectionSessionId, createdAt).takeIf { wasFailureActive }
        }

        val failureDelta = (counters.failuresTotal - baseline.failuresTotal).coerceAtLeast(0)
        val successDelta = (counters.queriesTotal - baseline.queriesTotal - failureDelta).coerceAtLeast(0)
        dnsBaseline = counters
        return when {
            successDelta > 0 -> {
                val wasFailureActive = dnsFailureActive
                dnsFailureStreak = 0
                recoverDns(connectionSessionId, createdAt).takeIf { wasFailureActive }
            }

            failureDelta > 0 -> {
                dnsFailureStreak += 1
                if (dnsFailureStreak >= DnsRuntimeFailureThreshold) {
                    dnsFailureActive = true
                    dnsRuntimeStateEvent(
                        connectionSessionId = connectionSessionId,
                        createdAt = createdAt,
                        state = "failure_threshold",
                        level = "warn",
                    )
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
    }

    fun acceptRelayHealth(
        serviceTelemetry: ServiceTelemetrySnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity? {
        val relayFailed = serviceTelemetry.hasRelayRuntimeFailure()
        return when {
            relayFailed -> {
                relayFailureActive = true
                relayRuntimeStateEvent(
                    connectionSessionId = connectionSessionId,
                    createdAt = createdAt,
                    relaySnapshot = serviceTelemetry.relayTelemetry,
                    relayFailed = true,
                    level = "warn",
                )
            }

            relayFailureActive -> {
                relayFailureActive = false
                relayRuntimeStateEvent(
                    connectionSessionId = connectionSessionId,
                    createdAt = createdAt,
                    relaySnapshot = serviceTelemetry.relayTelemetry,
                    relayFailed = false,
                    level = "info",
                )
            }

            else -> {
                null
            }
        }
    }

    private fun recoverDns(
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity {
        dnsFailureActive = false
        return dnsRuntimeStateEvent(
            connectionSessionId = connectionSessionId,
            createdAt = createdAt,
            state = "recovered",
            level = "info",
        )
    }
}

internal data class DnsCounterSnapshot(
    val producer: DnsCounterProducer,
    val queriesTotal: Long,
    val failuresTotal: Long,
) {
    fun hasDifferentProducerThan(previous: DnsCounterSnapshot): Boolean = producer != previous.producer

    fun hasRollbackFrom(previous: DnsCounterSnapshot): Boolean =
        queriesTotal < previous.queriesTotal || failuresTotal < previous.failuresTotal
}

internal data class DnsCounterProducer(
    val source: String,
    val serviceStartedAt: Long?,
    val restartCount: Int,
)

internal fun selectDnsCounterSource(telemetry: ServiceTelemetrySnapshot): DnsCounterSnapshot {
    val proxy = telemetry.proxyTelemetry
    val tunnel = telemetry.tunnelTelemetry
    val source = if (tunnel.dnsQueriesTotal >= proxy.dnsQueriesTotal) tunnel else proxy
    return DnsCounterSnapshot(
        producer =
            DnsCounterProducer(
                source = source.source,
                serviceStartedAt = telemetry.serviceStartedAt,
                restartCount = telemetry.restartCount,
            ),
        queriesTotal = source.dnsQueriesTotal.coerceAtLeast(0),
        failuresTotal = source.dnsFailuresTotal.coerceAtLeast(0),
    )
}

private fun dnsRuntimeStateEvent(
    connectionSessionId: String,
    createdAt: Long,
    state: String,
    level: String,
): NativeSessionEventEntity =
    typedRuntimeStateEvent(
        id = "typed_runtime_state:dns:$connectionSessionId",
        connectionSessionId = connectionSessionId,
        level = level,
        message = "event=dns_runtime_state evidence=dns_counter_transition_v1 state=$state",
        createdAt = createdAt,
        subsystem = "dns",
    )

private fun relayRuntimeStateEvent(
    connectionSessionId: String,
    createdAt: Long,
    relaySnapshot: NativeRuntimeSnapshot,
    relayFailed: Boolean,
    level: String,
): NativeSessionEventEntity =
    typedRuntimeStateEvent(
        id = "typed_runtime_state:relay:$connectionSessionId",
        connectionSessionId = connectionSessionId,
        level = level,
        message =
            "event=relay_runtime_state evidence=relay_health_transition_v1 " +
                "state=${relaySnapshot.state.toRelayRuntimeCategory(RelayRuntimeStates)} " +
                "health=${relaySnapshot.health.toRelayRuntimeCategory(RelayRuntimeHealthValues)} " +
                "relay_failed=$relayFailed",
        createdAt = createdAt,
        subsystem = "relay",
    )

private fun typedRuntimeStateEvent(
    id: String,
    connectionSessionId: String,
    level: String,
    message: String,
    createdAt: Long,
    subsystem: String,
): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = "service_telemetry_state",
        level = level,
        message = message,
        createdAt = createdAt,
        subsystem = subsystem,
    )

private fun ServiceTelemetrySnapshot.hasRelayRuntimeFailure(): Boolean =
    relayTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
        relayTelemetry.state.lowercase(Locale.US) in RelayRuntimeFailureStates ||
        relayTelemetry.health.lowercase(Locale.US) in RelayRuntimeFailureHealthValues

private fun String.toRelayRuntimeCategory(allowedValues: Set<String>): String {
    val normalized = lowercase(Locale.US).replace('-', '_')
    return normalized.takeIf(allowedValues::contains) ?: "unknown"
}

private const val DnsRuntimeFailureThreshold = 2
private val RelayRuntimeStates =
    setOf("idle", "starting", "running", "stopping", "stopped", "degraded", "failed", "error", "unknown")
private val RelayRuntimeHealthValues =
    setOf("idle", "ok", "healthy", "degraded", "failed", "error", "unknown")
private val RelayRuntimeFailureStates = setOf("failed", "error")
private val RelayRuntimeFailureHealthValues = setOf("failed", "error")
