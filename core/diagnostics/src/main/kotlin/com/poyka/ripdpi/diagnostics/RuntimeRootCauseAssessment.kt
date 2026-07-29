package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

internal const val RuntimeRootCauseAssessmentSchemaVersion = 1
internal const val RuntimeRootCauseAssessmentSource = "runtime_root_cause"
internal const val RuntimeRootCauseAssessmentSubsystem = "runtime_root_cause"

@Serializable
internal data class RuntimeRootCauseAssessment(
    val schemaVersion: Int = RuntimeRootCauseAssessmentSchemaVersion,
    val classifierId: String = "runtime_root_cause_v1",
    val verdict: RuntimeRootCauseVerdict,
    val confidence: RuntimeRootCauseConfidence,
    val evidenceEventCount: Int,
    val evidenceRefs: List<RuntimeRootCauseEvidenceRef>,
    val contradictoryCategories: List<String> = emptyList(),
    val terminalEvidenceSealed: Boolean = false,
    val bounded: Boolean = true,
)

@Serializable
internal data class RuntimeRootCauseEvidenceRef(
    val category: String,
    val count: Int,
    val firstSeenOffsetMillis: Long,
    val lastSeenOffsetMillis: Long,
)

@Serializable
internal enum class RuntimeRootCauseVerdict {
    @SerialName("UNDERLAY_LOST")
    UNDERLAY_LOST,

    @SerialName("VPN_ROUTE_LOOP")
    VPN_ROUTE_LOOP,

    @SerialName("VPN_PATH_LOSS")
    VPN_PATH_LOSS,

    @SerialName("DNS_FAILURE")
    DNS_FAILURE,

    @SerialName("RELAY_RUNTIME_FAILURE")
    RELAY_RUNTIME_FAILURE,

    @SerialName("INCONCLUSIVE")
    INCONCLUSIVE,
}

@Serializable
internal enum class RuntimeRootCauseConfidence {
    @SerialName("HIGH")
    HIGH,

    @SerialName("MEDIUM")
    MEDIUM,

    @SerialName("LOW")
    LOW,
}

internal object RuntimeRootCauseClassifier {
    fun assess(
        connectionSessionId: String,
        events: List<NativeSessionEventEntity>,
        networkTransitionEvents: List<NativeSessionEventEntity> = events,
        terminalAtMillis: Long = events.maxOfOrNull(NativeSessionEventEntity::createdAt) ?: 0L,
        terminalEvidenceSealed: Boolean = false,
    ): RuntimeRootCauseAssessment {
        val lowerBoundMillis = terminalAtMillis - RuntimeRootCauseWindowMillis
        val scopedNonTransitionEvents =
            events
                .asSequence()
                .filter { event -> event.connectionSessionId == connectionSessionId }
                .filterNot { event -> event.subsystem == RuntimeRootCauseAssessmentSubsystem }
                .filterNot { event -> event.subsystem == NetworkTransitionSubsystem }
                .filter { event -> event.createdAt in lowerBoundMillis..terminalAtMillis }
                .sortedBy(NativeSessionEventEntity::createdAt)
                .toList()
                .takeLast(MaxRuntimeRootCauseEvents)
        val transitionSelection =
            selectCanonicalNetworkTransitionEvents(
                connectionSessionId = connectionSessionId,
                events = networkTransitionEvents,
            )
        val effectiveTerminalEvidenceSealed = terminalEvidenceSealed && transitionSelection.complete
        val evidence =
            collectEvidence(
                connectionSessionId = connectionSessionId,
                events = scopedNonTransitionEvents,
                networkTransitionEvents = transitionSelection.events,
            )
        val verdict = selectVerdict(evidence, effectiveTerminalEvidenceSealed)
        return RuntimeRootCauseAssessment(
            verdict = verdict,
            confidence = confidenceFor(verdict, evidence),
            evidenceEventCount = scopedNonTransitionEvents.size + transitionSelection.events.size,
            evidenceRefs = evidence.refs(terminalAtMillis),
            contradictoryCategories = evidence.contradictoryCategories(verdict),
            terminalEvidenceSealed = effectiveTerminalEvidenceSealed,
        )
    }
}

private fun selectCanonicalNetworkTransitionEvents(
    connectionSessionId: String,
    events: List<NativeSessionEventEntity>,
): CanonicalNetworkTransitionSelection {
    val transitionEvents =
        events.filter { event ->
            event.connectionSessionId == connectionSessionId && event.subsystem == NetworkTransitionSubsystem
        }
    val sequencedEvents =
        transitionEvents.mapNotNull { event ->
            val sequence = event.transitionSequenceOrNull()
            if (event.isCanonicalNetworkTransition() && sequence != null && sequence > 0L) {
                sequence to event
            } else {
                null
            }
        }
    val complete =
        sequencedEvents.size == transitionEvents.size &&
            sequencedEvents.map(Pair<Long, NativeSessionEventEntity>::first).distinct().size == sequencedEvents.size
    return CanonicalNetworkTransitionSelection(
        events =
            if (complete) {
                sequencedEvents
                    .sortedBy(Pair<Long, NativeSessionEventEntity>::first)
                    .takeLast(MaxRuntimeRootCauseEvents)
                    .map(Pair<Long, NativeSessionEventEntity>::second)
            } else {
                emptyList()
            },
        complete = complete,
    )
}

private data class CanonicalNetworkTransitionSelection(
    val events: List<NativeSessionEventEntity>,
    val complete: Boolean,
)

private fun collectEvidence(
    connectionSessionId: String,
    events: List<NativeSessionEventEntity>,
    networkTransitionEvents: List<NativeSessionEventEntity>,
): RuntimeEvidenceAccumulator {
    val evidence = RuntimeEvidenceAccumulator()
    val terminalDataPlaneEvent = events.latestCanonicalDataPlaneFinalEvent()
    collectNetworkTransitionEvidence(networkTransitionEvents, evidence)
    events.forEach { event ->
        val tokens = event.message.toKeyValueTokens()
        val subsystem = event.subsystem.orEmpty()
        val level = event.level.lowercase(Locale.US)

        // Process/device-state events are supporting context until they have session-correlated exit evidence.
        when (subsystem) {
            "data_plane" -> {
                collectDataPlaneEvidence(event, tokens, terminalDataPlaneEvent, evidence)
            }

            "vpn_protect", "protect" -> {
                if (event.isCanonicalProtectFailure(tokens, level)) {
                    evidence.add(RuntimeEvidenceCategory.ProtectFailure, event)
                }
            }
        }
        // DNS and relay verdicts remain fail-closed until their producers emit an allowlisted kind.
        collectTypedRuntimeHealthEvidence(connectionSessionId, event, tokens, evidence)
    }
    return evidence
}

private fun collectNetworkTransitionEvidence(
    events: List<NativeSessionEventEntity>,
    evidence: RuntimeEvidenceAccumulator,
) {
    val reducer = NetworkTransitionEvidenceReducer()
    events
        .forEach(reducer::accept)
    reducer.unresolvedFailure?.let { event ->
        evidence.add(RuntimeEvidenceCategory.UnderlayLost, event)
    }
}

private class NetworkTransitionEvidenceReducer {
    private val authoritativeNonVpnGenerations = mutableSetOf<Long>()
    private val activeValidatedNonVpnGenerations = mutableSetOf<Long>()
    private val terminallyLostGenerations = mutableSetOf<Long>()
    private var unresolvedGeneration: Long? = null

    var unresolvedFailure: NativeSessionEventEntity? = null
        private set

    fun accept(event: NativeSessionEventEntity) {
        val tokens = event.message.toKeyValueTokens()
        val generation = tokens["generation"]?.toLongOrNull() ?: return
        val validated = tokens["validated"]
        val internet = tokens["internet"]
        if (tokens["path"] == "non_vpn" && hasAuthoritativeCapabilities(validated, internet)) {
            if (generation in terminallyLostGenerations) return
            authoritativeNonVpnGenerations.add(generation)
            acceptCapabilities(event, generation, validated, internet)
        } else if (tokens["kind"] == "lost" && generation in authoritativeNonVpnGenerations) {
            terminallyLostGenerations.add(generation)
            authoritativeNonVpnGenerations.remove(generation)
            activeValidatedNonVpnGenerations.remove(generation)
            if (activeValidatedNonVpnGenerations.isEmpty()) {
                recordFailure(event, generation)
            }
        }
    }

    private fun acceptCapabilities(
        event: NativeSessionEventEntity,
        generation: Long,
        validated: String?,
        internet: String?,
    ) {
        if (networkCapabilityMissing(validated, internet)) {
            activeValidatedNonVpnGenerations.remove(generation)
            if (activeValidatedNonVpnGenerations.isEmpty()) {
                recordFailure(event, generation)
            }
        } else {
            activeValidatedNonVpnGenerations.add(generation)
            unresolvedGeneration = null
            unresolvedFailure = null
        }
    }

    private fun recordFailure(
        event: NativeSessionEventEntity,
        generation: Long,
    ) {
        val failedGeneration = unresolvedGeneration
        if (failedGeneration == null || generation >= failedGeneration) {
            unresolvedGeneration = generation
            unresolvedFailure = event
        }
    }
}

private fun collectDataPlaneEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    terminalEvent: NativeSessionEventEntity?,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (event != terminalEvent) return
    when (tokens["state"]) {
        "tun_ingress_no_upstream" -> evidence.add(RuntimeEvidenceCategory.DataPlaneTunIngressNoUpstream, event)
        "outbound_only" -> evidence.add(RuntimeEvidenceCategory.DataPlaneOutboundNoReturn, event)
        "cross_layer_return_observed" -> evidence.add(RuntimeEvidenceCategory.DataPlaneForwardingHealthy, event)
    }
}

private fun collectTypedRuntimeHealthEvidence(
    connectionSessionId: String,
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (event.source != "service_telemetry_state") return
    if (event.level.lowercase(Locale.US) !in TypedRuntimeHealthLevels) return
    when (event.subsystem) {
        "dns" -> {
            if (event.isTerminalDnsFailure(connectionSessionId, tokens)) {
                evidence.add(RuntimeEvidenceCategory.DnsFailure, event)
            }
        }

        "relay" -> {
            if (event.isTerminalRelayState(connectionSessionId, tokens)) {
                if (tokens["relay_failed"] == "true") {
                    evidence.add(RuntimeEvidenceCategory.RelayRuntimeFailure, event)
                }
            }
        }
    }
}

private fun NativeSessionEventEntity.isTerminalDnsFailure(
    connectionSessionId: String,
    tokens: Map<String, String>,
): Boolean {
    val matchesIdentity = id == "typed_runtime_state:dns:$connectionSessionId"
    val matchesTransition =
        tokens["event"] == "dns_runtime_state" &&
            tokens["evidence"] == "dns_counter_transition_v1"
    return matchesIdentity && matchesTransition && tokens["state"] == "failure_threshold"
}

private fun NativeSessionEventEntity.isTerminalRelayState(
    connectionSessionId: String,
    tokens: Map<String, String>,
): Boolean {
    val matchesIdentity = id == "typed_runtime_state:relay:$connectionSessionId"
    val matchesTransition =
        tokens["event"] == "relay_runtime_state" &&
            tokens["evidence"] == "relay_health_transition_v1"
    val matchesVerdict =
        tokens["state"] in RelayRuntimeStates &&
            tokens["health"] in RelayRuntimeHealthValues &&
            tokens["relay_failed"] in RelayFailedValues
    return matchesIdentity && matchesTransition && matchesVerdict
}

private fun selectVerdict(
    evidence: RuntimeEvidenceAccumulator,
    terminalEvidenceSealed: Boolean,
): RuntimeRootCauseVerdict {
    val terminalPathVerdicts =
        (
            selectDirectVerdicts(evidence) +
                listOfNotNull(
                    RuntimeRootCauseVerdict.UNDERLAY_LOST.takeIf {
                        evidence.has(RuntimeEvidenceCategory.UnderlayLost)
                    },
                )
        ).distinct()
    return when {
        !terminalEvidenceSealed -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        terminalPathVerdicts.size == 1 -> {
            terminalPathVerdicts.single()
        }

        terminalPathVerdicts.isNotEmpty() -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        evidence.has(RuntimeEvidenceCategory.DataPlaneForwardingHealthy) -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        evidence.hasDataPlaneConflict() -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        else -> {
            selectDataPlaneVerdict(evidence)
        }
    }
}

private fun selectDirectVerdicts(evidence: RuntimeEvidenceAccumulator): List<RuntimeRootCauseVerdict> =
    listOfNotNull(
        RuntimeRootCauseVerdict.DNS_FAILURE.takeIf {
            evidence.has(RuntimeEvidenceCategory.DnsFailure)
        },
        RuntimeRootCauseVerdict.RELAY_RUNTIME_FAILURE.takeIf {
            evidence.has(RuntimeEvidenceCategory.RelayRuntimeFailure)
        },
    )

private fun selectDataPlaneVerdict(evidence: RuntimeEvidenceAccumulator): RuntimeRootCauseVerdict {
    val hasRouteLoop = evidence.has(RuntimeEvidenceCategory.DataPlaneTunIngressNoUpstream)
    val hasPathLoss =
        evidence.has(RuntimeEvidenceCategory.DataPlaneOutboundNoReturn) ||
            evidence.has(RuntimeEvidenceCategory.ProtectFailure)
    return when {
        hasRouteLoop && hasPathLoss -> RuntimeRootCauseVerdict.INCONCLUSIVE
        hasRouteLoop -> RuntimeRootCauseVerdict.VPN_ROUTE_LOOP
        hasPathLoss -> RuntimeRootCauseVerdict.VPN_PATH_LOSS
        else -> RuntimeRootCauseVerdict.INCONCLUSIVE
    }
}

private fun confidenceFor(
    verdict: RuntimeRootCauseVerdict,
    evidence: RuntimeEvidenceAccumulator,
): RuntimeRootCauseConfidence {
    if (verdict == RuntimeRootCauseVerdict.INCONCLUSIVE) return RuntimeRootCauseConfidence.LOW
    val categories = verdict.evidenceCategories()
    val primaryCount = categories.sumOf(evidence::count)
    val hasTerminalDataPlane =
        evidence.has(RuntimeEvidenceCategory.DataPlaneTunIngressNoUpstream) ||
            evidence.has(RuntimeEvidenceCategory.DataPlaneOutboundNoReturn)
    return when {
        verdict == RuntimeRootCauseVerdict.DNS_FAILURE -> RuntimeRootCauseConfidence.MEDIUM
        primaryCount >= 2 -> RuntimeRootCauseConfidence.HIGH
        hasTerminalDataPlane && verdict in DataPlaneSupportedVerdicts -> RuntimeRootCauseConfidence.HIGH
        else -> RuntimeRootCauseConfidence.MEDIUM
    }
}

private class RuntimeEvidenceAccumulator {
    private val buckets = linkedMapOf<RuntimeEvidenceCategory, RuntimeEvidenceBucket>()

    fun add(
        category: RuntimeEvidenceCategory,
        event: NativeSessionEventEntity,
    ) {
        val bucket = buckets.getOrPut(category) { RuntimeEvidenceBucket(category.wireValue) }
        bucket.record(event.createdAt)
    }

    fun has(category: RuntimeEvidenceCategory): Boolean = buckets.containsKey(category)

    fun count(category: RuntimeEvidenceCategory): Int = buckets[category]?.count ?: 0

    fun hasDataPlaneConflict(): Boolean =
        has(RuntimeEvidenceCategory.DataPlaneTunIngressNoUpstream) &&
            (
                has(RuntimeEvidenceCategory.DataPlaneOutboundNoReturn) ||
                    has(RuntimeEvidenceCategory.ProtectFailure)
            )

    fun refs(terminalAtMillis: Long): List<RuntimeRootCauseEvidenceRef> =
        buckets.values
            .sortedWith(compareBy<RuntimeEvidenceBucket> { it.firstSeenAt }.thenBy { it.category })
            .take(MaxRuntimeRootCauseEvidenceRefs)
            .map { bucket -> bucket.toRef(terminalAtMillis) }

    fun contradictoryCategories(verdict: RuntimeRootCauseVerdict): List<String> {
        if (verdict == RuntimeRootCauseVerdict.INCONCLUSIVE) {
            return buckets.keys
                .filterNot { category -> category == RuntimeEvidenceCategory.DataPlaneForwardingHealthy }
                .map(RuntimeEvidenceCategory::wireValue)
                .sorted()
        }
        val allowed = verdict.evidenceCategories()
        return buckets.keys
            .filterNot { category -> category in allowed }
            .filterNot { category ->
                category == RuntimeEvidenceCategory.DataPlaneForwardingHealthy
            }.map(RuntimeEvidenceCategory::wireValue)
            .sorted()
    }
}

private class RuntimeEvidenceBucket(
    val category: String,
) {
    var count: Int = 0
        private set
    var firstSeenAt: Long = Long.MAX_VALUE
        private set
    var lastSeenAt: Long = Long.MIN_VALUE
        private set

    fun record(createdAt: Long) {
        count += 1
        firstSeenAt = minOf(firstSeenAt, createdAt)
        lastSeenAt = maxOf(lastSeenAt, createdAt)
    }

    fun toRef(terminalAtMillis: Long): RuntimeRootCauseEvidenceRef =
        RuntimeRootCauseEvidenceRef(
            category = category,
            count = count,
            firstSeenOffsetMillis = (terminalAtMillis - firstSeenAt).coerceAtLeast(0L),
            lastSeenOffsetMillis = (terminalAtMillis - lastSeenAt).coerceAtLeast(0L),
        )
}

private enum class RuntimeEvidenceCategory(
    val wireValue: String,
) {
    UnderlayLost("network_transition_underlay_lost"),
    DataPlaneTunIngressNoUpstream("data_plane_tun_ingress_no_upstream"),
    DataPlaneOutboundNoReturn("data_plane_outbound_no_return"),
    DataPlaneForwardingHealthy("data_plane_forwarding_healthy"),
    DnsFailure("dns_failure"),
    RelayRuntimeFailure("relay_runtime_failure"),
    ProtectFailure("protect_failure"),
}

private fun RuntimeRootCauseVerdict.evidenceCategories(): Set<RuntimeEvidenceCategory> =
    when (this) {
        RuntimeRootCauseVerdict.UNDERLAY_LOST -> {
            setOf(RuntimeEvidenceCategory.UnderlayLost)
        }

        RuntimeRootCauseVerdict.VPN_ROUTE_LOOP -> {
            setOf(RuntimeEvidenceCategory.DataPlaneTunIngressNoUpstream)
        }

        RuntimeRootCauseVerdict.VPN_PATH_LOSS -> {
            setOf(
                RuntimeEvidenceCategory.DataPlaneOutboundNoReturn,
                RuntimeEvidenceCategory.ProtectFailure,
            )
        }

        RuntimeRootCauseVerdict.DNS_FAILURE -> {
            setOf(RuntimeEvidenceCategory.DnsFailure)
        }

        RuntimeRootCauseVerdict.RELAY_RUNTIME_FAILURE -> {
            setOf(RuntimeEvidenceCategory.RelayRuntimeFailure)
        }

        RuntimeRootCauseVerdict.INCONCLUSIVE -> {
            emptySet()
        }
    }

internal fun String.toKeyValueTokens(): Map<String, String> =
    split(' ', ';')
        .asSequence()
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return@mapNotNull null
            val key = token.substring(0, separator).lowercase(Locale.US)
            val value = token.substring(separator + 1).trim(',', '"').lowercase(Locale.US)
            key to value
        }.toMap()

internal fun NativeSessionEventEntity.toKeyValueTokens(): Map<String, String> = message.toKeyValueTokens()

private fun NativeSessionEventEntity.transitionSequenceOrNull(): Long? =
    if (subsystem == NetworkTransitionSubsystem) {
        message.toKeyValueTokens()["sequence"]?.toLongOrNull()
    } else {
        null
    }

private fun NativeSessionEventEntity.isCanonicalNetworkTransition(): Boolean {
    val tokens = message.toKeyValueTokens()
    return source == NetworkTransitionSource &&
        level.lowercase(Locale.US) == "info" &&
        subsystem == NetworkTransitionSubsystem &&
        tokens["kind"] in NetworkTransitionKinds &&
        tokens["generation"]?.toLongOrNull() != null &&
        tokens["sequence"]?.toLongOrNull() != null
}

private fun NativeSessionEventEntity.isCanonicalProtectFailure(
    tokens: Map<String, String>,
    level: String,
): Boolean =
    source == "service" &&
        subsystem in ProtectSubsystems &&
        level in WarningLevels &&
        tokens["event_kind"] == "protect_failure" &&
        tokens["event"] == "protect_failed"

private fun networkCapabilityMissing(
    validated: String?,
    internet: String?,
): Boolean = validated == "absent" || internet == "absent"

private fun hasAuthoritativeCapabilities(
    validated: String?,
    internet: String?,
): Boolean = validated in NetworkCapabilityStates && internet in NetworkCapabilityStates

internal fun List<NativeSessionEventEntity>.hasCanonicalDataPlaneFinalEvent(): Boolean =
    latestCanonicalDataPlaneFinalEvent() != null

private fun List<NativeSessionEventEntity>.latestCanonicalDataPlaneFinalEvent(): NativeSessionEventEntity? =
    asSequence()
        .filter { event -> event.source == "service" }
        .filter { event -> event.level.lowercase(Locale.US) == "info" }
        .filter { event -> event.subsystem == "data_plane" }
        .filter { event ->
            val tokens = event.message.toKeyValueTokens()
            tokens["event_kind"] == "data_plane_final" &&
                tokens["final"] == "true" &&
                tokens["generation"]?.toLongOrNull() != null &&
                tokens["mode"] in DataPlaneModes &&
                tokens["state"] in DataPlaneCorrelationStates
        }.maxWithOrNull(
            compareBy<NativeSessionEventEntity> { event -> event.createdAt }
                .thenBy { event -> event.message.toKeyValueTokens()["generation"]?.toLongOrNull() }
                .thenBy { event -> event.id },
        )

private const val MaxRuntimeRootCauseEvents = 64
private const val MaxRuntimeRootCauseEvidenceRefs = 8
private const val RuntimeRootCauseWindowMillis = 5 * 60 * 1000L
private const val NetworkTransitionSubsystem = "network_transition"
private const val NetworkTransitionSource = "android_network_callback"

private val WarningLevels = setOf("warn", "error")
private val TypedRuntimeHealthLevels = setOf("info", "warn")
private val NetworkCapabilityStates = setOf("present", "absent")
private val NetworkTransitionKinds =
    setOf("available", "losing", "lost", "capabilities_changed", "link_properties_changed")
private val ProtectSubsystems = setOf("vpn_protect", "protect")
private val RelayFailedValues = setOf("true", "false")
private val RelayRuntimeStates =
    setOf("idle", "starting", "running", "stopping", "stopped", "degraded", "failed", "error", "unknown")
private val RelayRuntimeHealthValues =
    setOf("idle", "ok", "healthy", "degraded", "failed", "error", "unknown")
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
private val DataPlaneSupportedVerdicts =
    setOf(RuntimeRootCauseVerdict.VPN_ROUTE_LOOP, RuntimeRootCauseVerdict.VPN_PATH_LOSS)
