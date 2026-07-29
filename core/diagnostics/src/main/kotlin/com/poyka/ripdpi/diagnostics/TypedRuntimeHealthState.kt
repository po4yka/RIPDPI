package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import java.util.Locale

internal data class TypedRuntimeHealthState(
    private val dnsBaseline: DnsCounterSnapshot? = null,
    private val dnsFailureStreak: Int = 0,
    private val dnsFailureActive: Boolean = false,
    private val relayFailureActive: Boolean = false,
) {
    fun reduce(
        serviceTelemetry: ServiceTelemetrySnapshot,
        connectionSessionId: String,
    ): TypedRuntimeHealthTransition {
        val dnsTransition =
            reduceDnsCounters(
                counters = selectDnsCounterSource(serviceTelemetry),
                connectionSessionId = connectionSessionId,
                createdAt = serviceTelemetry.updatedAt,
            )
        val relayTransition =
            dnsTransition.nextState.reduceRelayHealth(
                serviceTelemetry = serviceTelemetry,
                connectionSessionId = connectionSessionId,
                createdAt = serviceTelemetry.updatedAt,
            )
        return TypedRuntimeHealthTransition(
            nextState = relayTransition.nextState,
            events = listOfNotNull(dnsTransition.event, relayTransition.event),
        )
    }

    private fun reduceDnsCounters(
        counters: DnsCounterSnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): TypedRuntimeHealthStep {
        val baseline = dnsBaseline
        if (baseline == null) {
            return TypedRuntimeHealthStep(
                nextState = copy(dnsBaseline = counters, dnsFailureStreak = 0, dnsFailureActive = false),
            )
        }
        return if (counters.hasDifferentProducerThan(baseline) || counters.hasRollbackFrom(baseline)) {
            resetDnsBaseline(counters, connectionSessionId, createdAt)
        } else {
            val failureDelta = (counters.failuresTotal - baseline.failuresTotal).coerceAtLeast(0)
            val successDelta = (counters.queriesTotal - baseline.queriesTotal - failureDelta).coerceAtLeast(0)
            when {
                successDelta > 0 -> {
                    TypedRuntimeHealthStep(
                        nextState = copy(dnsBaseline = counters, dnsFailureStreak = 0, dnsFailureActive = false),
                        event = recoverDns(connectionSessionId, createdAt).takeIf { dnsFailureActive },
                    )
                }

                failureDelta > 0 -> {
                    val nextFailureStreak = dnsFailureStreak + 1
                    val thresholdReached = nextFailureStreak >= DnsRuntimeFailureThreshold
                    TypedRuntimeHealthStep(
                        nextState =
                            copy(
                                dnsBaseline = counters,
                                dnsFailureStreak = nextFailureStreak,
                                dnsFailureActive = dnsFailureActive || thresholdReached,
                            ),
                        event =
                            dnsRuntimeStateEvent(
                                connectionSessionId = connectionSessionId,
                                createdAt = createdAt,
                                state = "failure_threshold",
                                level = "warn",
                            ).takeIf { thresholdReached },
                    )
                }

                else -> {
                    TypedRuntimeHealthStep(nextState = copy(dnsBaseline = counters))
                }
            }
        }
    }

    private fun reduceRelayHealth(
        serviceTelemetry: ServiceTelemetrySnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): TypedRuntimeHealthStep {
        val relayFailed = serviceTelemetry.hasRelayRuntimeFailure()
        return when {
            relayFailed -> {
                TypedRuntimeHealthStep(
                    nextState = copy(relayFailureActive = true),
                    event =
                        relayRuntimeStateEvent(
                            connectionSessionId = connectionSessionId,
                            createdAt = createdAt,
                            relaySnapshot = serviceTelemetry.relayTelemetry,
                            relayFailed = true,
                            level = "warn",
                        ),
                )
            }

            relayFailureActive -> {
                TypedRuntimeHealthStep(
                    nextState = copy(relayFailureActive = false),
                    event =
                        relayRuntimeStateEvent(
                            connectionSessionId = connectionSessionId,
                            createdAt = createdAt,
                            relaySnapshot = serviceTelemetry.relayTelemetry,
                            relayFailed = false,
                            level = "info",
                        ),
                )
            }

            else -> {
                TypedRuntimeHealthStep(nextState = this)
            }
        }
    }

    private fun resetDnsBaseline(
        counters: DnsCounterSnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): TypedRuntimeHealthStep =
        TypedRuntimeHealthStep(
            nextState = copy(dnsBaseline = counters, dnsFailureStreak = 0, dnsFailureActive = false),
            event = recoverDns(connectionSessionId, createdAt).takeIf { dnsFailureActive },
        )

    private fun recoverDns(
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity =
        dnsRuntimeStateEvent(
            connectionSessionId = connectionSessionId,
            createdAt = createdAt,
            state = "recovered",
            level = "info",
        )
}

internal data class TypedRuntimeHealthTransition(
    val nextState: TypedRuntimeHealthState,
    val events: List<NativeSessionEventEntity>,
)

private data class TypedRuntimeHealthStep(
    val nextState: TypedRuntimeHealthState,
    val event: NativeSessionEventEntity? = null,
)

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
