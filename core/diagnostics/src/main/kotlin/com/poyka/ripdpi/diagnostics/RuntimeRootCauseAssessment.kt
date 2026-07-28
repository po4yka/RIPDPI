package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.exit.ProcessExitCorrelationSource
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

    @SerialName("OEM_PROCESS_KILL")
    OEM_PROCESS_KILL,

    @SerialName("VPN_ROUTE_LOOP")
    VPN_ROUTE_LOOP,

    @SerialName("VPN_PATH_LOSS")
    VPN_PATH_LOSS,

    @SerialName("DNS_FAILURE")
    DNS_FAILURE,

    @SerialName("MTU_BLACKHOLE")
    MTU_BLACKHOLE,

    @SerialName("RELAY_STALL")
    RELAY_STALL,

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
        terminalAtMillis: Long = events.maxOfOrNull(NativeSessionEventEntity::createdAt) ?: 0L,
        terminalEvidenceSealed: Boolean = true,
    ): RuntimeRootCauseAssessment {
        val lowerBoundMillis = terminalAtMillis - RuntimeRootCauseWindowMillis
        val scopedEvents =
            events
                .asSequence()
                .filter { event -> event.connectionSessionId == connectionSessionId }
                .filterNot { event -> event.subsystem == RuntimeRootCauseAssessmentSubsystem }
                .filter { event -> event.createdAt in lowerBoundMillis..terminalAtMillis }
                .sortedWith(
                    compareBy<NativeSessionEventEntity>(NativeSessionEventEntity::createdAt)
                        .thenBy { event -> event.transitionSequence() },
                ).toList()
                .takeLast(MaxRuntimeRootCauseEvents)
        val evidence = collectEvidence(connectionSessionId, scopedEvents)
        val verdict = selectVerdict(evidence, terminalEvidenceSealed)
        return RuntimeRootCauseAssessment(
            verdict = verdict,
            confidence = confidenceFor(verdict, evidence),
            evidenceEventCount = scopedEvents.size,
            evidenceRefs = evidence.refs(terminalAtMillis),
            contradictoryCategories = evidence.contradictoryCategories(verdict),
            terminalEvidenceSealed = terminalEvidenceSealed,
        )
    }
}

private fun collectEvidence(
    connectionSessionId: String,
    events: List<NativeSessionEventEntity>,
): RuntimeEvidenceAccumulator {
    val evidence = RuntimeEvidenceAccumulator()
    val terminalDataPlaneEvent = events.latestCanonicalDataPlaneFinalEvent()
    collectNetworkTransitionEvidence(events, evidence)
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
                if (level in WarningLevels) {
                    evidence.add(RuntimeEvidenceCategory.ProtectFailure, event)
                }
            }
        }
        // DNS and relay verdicts remain fail-closed until their producers emit an allowlisted kind.
        collectTypedRuntimeHealthEvidence(connectionSessionId, event, tokens, evidence)
        collectCorrelatedProcessExitEvidence(connectionSessionId, event, tokens, evidence)
        collectExplicitMtuEvidence(event, tokens, evidence)
    }
    return evidence
}

private fun collectNetworkTransitionEvidence(
    events: List<NativeSessionEventEntity>,
    evidence: RuntimeEvidenceAccumulator,
) {
    val reducer = NetworkTransitionEvidenceReducer()
    events
        .asSequence()
        .filter { event -> event.subsystem == "network_transition" }
        .sortedWith(
            compareBy<NativeSessionEventEntity> { event -> event.transitionSequenceOrNull() ?: Long.MAX_VALUE }
                .thenBy(NativeSessionEventEntity::createdAt),
        ).forEach(reducer::accept)
    reducer.unresolvedFailure?.let { event ->
        evidence.add(RuntimeEvidenceCategory.UnderlayLost, event)
    }
}

private class NetworkTransitionEvidenceReducer {
    private val authoritativeNonVpnGenerations = mutableSetOf<Long>()
    private var unresolvedGeneration: Long? = null
    private var unresolvedFromLost: Boolean = false

    var unresolvedFailure: NativeSessionEventEntity? = null
        private set

    fun accept(event: NativeSessionEventEntity) {
        val tokens = event.message.toKeyValueTokens()
        val generation = tokens["generation"]?.toLongOrNull() ?: return
        val validated = tokens["validated"]
        val internet = tokens["internet"]
        if (tokens["path"] == "non_vpn" && hasAuthoritativeCapabilities(validated, internet)) {
            authoritativeNonVpnGenerations.add(generation)
            acceptCapabilities(event, generation, validated, internet)
        } else if (tokens["kind"] == "lost" && generation in authoritativeNonVpnGenerations) {
            recordFailure(event, generation, fromLost = true)
        }
    }

    private fun acceptCapabilities(
        event: NativeSessionEventEntity,
        generation: Long,
        validated: String?,
        internet: String?,
    ) {
        if (networkCapabilityMissing(validated, internet)) {
            recordFailure(event, generation, fromLost = false)
        } else if (canRecoverAt(generation)) {
            unresolvedGeneration = null
            unresolvedFromLost = false
            unresolvedFailure = null
        }
    }

    private fun canRecoverAt(generation: Long): Boolean {
        val failedGeneration = unresolvedGeneration ?: return false
        return generation > failedGeneration || (generation == failedGeneration && !unresolvedFromLost)
    }

    private fun recordFailure(
        event: NativeSessionEventEntity,
        generation: Long,
        fromLost: Boolean,
    ) {
        val failedGeneration = unresolvedGeneration
        if (failedGeneration == null || generation >= failedGeneration) {
            unresolvedGeneration = generation
            unresolvedFromLost = fromLost
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
            if (
                event.id == "typed_runtime_state:dns:$connectionSessionId" &&
                tokens["event"] == "dns_runtime_state" &&
                tokens["evidence"] == "dns_counter_transition_v1" &&
                tokens["state"] == "failure_threshold"
            ) {
                evidence.add(RuntimeEvidenceCategory.DnsFailure, event)
            }
        }

        "relay" -> {
            if (
                event.id == "typed_runtime_state:relay:$connectionSessionId" &&
                tokens["event"] == "relay_runtime_state" &&
                tokens["evidence"] == "relay_health_transition_v1" &&
                tokens["state"] in RelayRuntimeStates &&
                tokens["health"] in RelayRuntimeHealthValues &&
                tokens["relay_failed"] in RelayFailedValues
            ) {
                if (tokens["relay_failed"] == "true") {
                    evidence.add(RuntimeEvidenceCategory.RelayRuntimeFailure, event)
                }
            }
        }
    }
}

private fun collectCorrelatedProcessExitEvidence(
    connectionSessionId: String,
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (event.id != "$ProcessExitCorrelationSource:$connectionSessionId") return
    if (event.source != ProcessExitCorrelationSource) return
    if (event.subsystem != "process") return
    if (event.level.lowercase(Locale.US) != "warn") return
    if (
        tokens["event"] == "process_exit_correlation" &&
        tokens["verdict"] == "oem_process_kill" &&
        tokens["evidence"] == "last_exit_inspector_v1" &&
        tokens.isQualifyingOemProcessKill()
    ) {
        evidence.add(RuntimeEvidenceCategory.OemProcessKill, event)
    }
}

private fun collectExplicitMtuEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (event.subsystem == "pmtu" && tokens.hasExactPmtuBlackholeEvidence()) {
        evidence.add(RuntimeEvidenceCategory.MtuBlackhole, event)
    }
}

private fun selectVerdict(
    evidence: RuntimeEvidenceAccumulator,
    terminalEvidenceSealed: Boolean,
): RuntimeRootCauseVerdict {
    if (!terminalEvidenceSealed) return RuntimeRootCauseVerdict.INCONCLUSIVE
    return when {
        evidence.has(RuntimeEvidenceCategory.DataPlaneForwardingHealthy) -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        evidence.hasDataPlaneConflict() -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        evidence.has(RuntimeEvidenceCategory.RelayRuntimeFailure) -> {
            RuntimeRootCauseVerdict.INCONCLUSIVE
        }

        else -> {
            val candidateVerdicts =
                (
                    selectDirectVerdicts(evidence) +
                        selectDataPlaneVerdict(evidence) +
                        listOfNotNull(
                            RuntimeRootCauseVerdict.UNDERLAY_LOST.takeIf {
                                evidence.has(RuntimeEvidenceCategory.UnderlayLost)
                            },
                        )
                ).filterNot { verdict -> verdict == RuntimeRootCauseVerdict.INCONCLUSIVE }
                    .distinct()
            candidateVerdicts.singleOrNull() ?: RuntimeRootCauseVerdict.INCONCLUSIVE
        }
    }
}

private fun selectDirectVerdicts(evidence: RuntimeEvidenceAccumulator): List<RuntimeRootCauseVerdict> =
    listOfNotNull(
        RuntimeRootCauseVerdict.DNS_FAILURE.takeIf {
            evidence.has(RuntimeEvidenceCategory.DnsFailure)
        },
        RuntimeRootCauseVerdict.OEM_PROCESS_KILL.takeIf {
            evidence.has(RuntimeEvidenceCategory.OemProcessKill)
        },
        RuntimeRootCauseVerdict.MTU_BLACKHOLE.takeIf {
            evidence.has(RuntimeEvidenceCategory.MtuBlackhole)
        },
        RuntimeRootCauseVerdict.RELAY_STALL.takeIf {
            evidence.has(RuntimeEvidenceCategory.RelayStall)
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
        verdict == RuntimeRootCauseVerdict.OEM_PROCESS_KILL -> RuntimeRootCauseConfidence.MEDIUM
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
    OemProcessKill("oem_process_kill"),
    MtuBlackhole("mtu_blackhole"),
    RelayStall("relay_stall"),
    RelayRuntimeFailure("relay_runtime_failure"),
    ProtectFailure("protect_failure"),
}

private fun RuntimeRootCauseVerdict.evidenceCategories(): Set<RuntimeEvidenceCategory> =
    when (this) {
        RuntimeRootCauseVerdict.UNDERLAY_LOST -> {
            setOf(RuntimeEvidenceCategory.UnderlayLost)
        }

        RuntimeRootCauseVerdict.OEM_PROCESS_KILL -> {
            setOf(RuntimeEvidenceCategory.OemProcessKill)
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

        RuntimeRootCauseVerdict.MTU_BLACKHOLE -> {
            setOf(RuntimeEvidenceCategory.MtuBlackhole)
        }

        RuntimeRootCauseVerdict.RELAY_STALL -> {
            setOf(RuntimeEvidenceCategory.RelayStall)
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

private fun NativeSessionEventEntity.transitionSequence(): Long = transitionSequenceOrNull() ?: Long.MIN_VALUE

private fun NativeSessionEventEntity.transitionSequenceOrNull(): Long? =
    if (subsystem == "network_transition") {
        message.toKeyValueTokens()["sequence"]?.toLongOrNull()
    } else {
        null
    }

private fun networkCapabilityMissing(
    validated: String?,
    internet: String?,
): Boolean = validated == "absent" || internet == "absent"

private fun hasAuthoritativeCapabilities(
    validated: String?,
    internet: String?,
): Boolean = validated in NetworkCapabilityStates && internet in NetworkCapabilityStates

private fun Map<String, String>.isQualifyingOemProcessKill(): Boolean {
    val reason = this["reason"]
    val subtype = this["subtype"]
    val reasonQualified =
        reason in OemProcessKillReasons ||
            (reason == "other" && subtype == "android_memory_limiter")
    return reasonQualified && this["importance"] in OemProcessKillImportanceBands
}

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

private fun Map<String, String>.hasExactPmtuBlackholeEvidence(): Boolean =
    this["verdict"] == "mtu_blackhole" &&
        this["provenance"] in PmtuProbeProvenance &&
        this["small_control"] == "success" &&
        this["larger_failures"]?.toIntOrNull()?.let { count -> count >= MinPmtuLargeFailures } == true &&
        this["post_control_recovery"] == "success" &&
        this["ptb_observation"] == "not_observed_in_run"

private const val MaxRuntimeRootCauseEvents = 64
private const val MaxRuntimeRootCauseEvidenceRefs = 8
private const val RuntimeRootCauseWindowMillis = 5 * 60 * 1000L
private const val MinPmtuLargeFailures = 2

private val WarningLevels = setOf("warn", "error")
private val TypedRuntimeHealthLevels = setOf("info", "warn")
private val PmtuProbeProvenance = setOf("pmtu_probe_v1", "typed_pmtu_probe_v1")
private val NetworkCapabilityStates = setOf("present", "absent")
private val OemProcessKillReasons = setOf("low_memory", "excessive_resource_usage")
private val OemProcessKillImportanceBands = setOf("foreground_service", "service", "perceptible")
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
