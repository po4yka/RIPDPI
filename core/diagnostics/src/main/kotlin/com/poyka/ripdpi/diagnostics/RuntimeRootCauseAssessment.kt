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
    ): RuntimeRootCauseAssessment {
        val lowerBoundMillis = terminalAtMillis - RuntimeRootCauseWindowMillis
        val scopedEvents =
            events
                .asSequence()
                .filter { event -> event.connectionSessionId == connectionSessionId }
                .filterNot { event -> event.subsystem == RuntimeRootCauseAssessmentSubsystem }
                .filter { event -> event.createdAt in lowerBoundMillis..terminalAtMillis }
                .sortedBy(NativeSessionEventEntity::createdAt)
                .toList()
                .takeLast(MaxRuntimeRootCauseEvents)
        val evidence = collectEvidence(scopedEvents)
        val verdict = selectVerdict(evidence)
        return RuntimeRootCauseAssessment(
            verdict = verdict,
            confidence = confidenceFor(verdict, evidence),
            evidenceEventCount = scopedEvents.size,
            evidenceRefs = evidence.refs(terminalAtMillis),
            contradictoryCategories = evidence.contradictoryCategories(verdict),
        )
    }
}

private fun collectEvidence(events: List<NativeSessionEventEntity>): RuntimeEvidenceAccumulator {
    val evidence = RuntimeEvidenceAccumulator()
    events.forEach { event ->
        val tokens = event.message.toKeyValueTokens()
        val subsystem = event.subsystem.orEmpty()
        val source = event.source
        val level = event.level.lowercase(Locale.US)

        when (subsystem) {
            "network_transition" -> {
                collectNetworkTransitionEvidence(event, tokens, evidence)
            }

            "device_state" -> {
                collectDeviceStateEvidence(event, tokens, evidence)
            }

            "process" -> {
                collectProcessEvidence(event, tokens, evidence)
            }

            "data_plane" -> {
                collectDataPlaneEvidence(event, tokens, evidence)
            }

            "dns" -> {
                collectDnsEvidence(event, tokens, level, evidence)
            }

            "relay" -> {
                collectRelayEvidence(event, tokens, level, evidence)
            }

            "vpn_protect", "protect" -> {
                if (level in WarningLevels) {
                    evidence.add(RuntimeEvidenceCategory.ProtectFailure, event)
                }
            }
        }
        if (source == "dns") collectDnsEvidence(event, tokens, level, evidence)
        if (source == "relay") collectRelayEvidence(event, tokens, level, evidence)
        collectExplicitMtuEvidence(event, tokens, evidence)
    }
    return evidence
}

private fun collectNetworkTransitionEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    val kind = tokens["kind"]
    val path = tokens["path"]
    val validated = tokens["validated"]
    val internet = tokens["internet"]
    val underlayLost = kind == "lost"
    val underlayUnvalidated = path == "non_vpn" && networkCapabilityMissing(validated, internet)
    if (underlayLost || underlayUnvalidated) {
        evidence.add(RuntimeEvidenceCategory.UnderlayLost, event)
    }
}

private fun collectDeviceStateEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    val terminalService = tokens["service_lifecycle"] == "destroyed" || tokens["trigger"] == "service_destroyed"
    val foregroundDenied = tokens["foreground_outcome"] in ForegroundFailureOutcomes
    val constrained =
        tokens["background_restricted"] == "enabled" ||
            tokens["power_saver"] == "enabled" ||
            tokens["memory_trim_callback"] in setOf("background", "moderate", "critical")
    if ((terminalService || foregroundDenied) && constrained) {
        evidence.add(RuntimeEvidenceCategory.DeviceProcessKill, event)
    }
}

private fun collectProcessEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (tokens["reason"] in ProcessKillReasons || tokens["subtype"] == "android_memory_limiter") {
        evidence.add(RuntimeEvidenceCategory.DeviceProcessKill, event)
    }
}

private fun collectDataPlaneEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    when (tokens["state"]) {
        "tun_ingress_no_upstream" -> evidence.add(RuntimeEvidenceCategory.DataPlaneTunIngressNoUpstream, event)
        "outbound_only" -> evidence.add(RuntimeEvidenceCategory.DataPlaneOutboundNoReturn, event)
        "cross_layer_return_observed" -> evidence.add(RuntimeEvidenceCategory.DataPlaneForwardingHealthy, event)
    }
}

private fun collectRelayEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    level: String,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (tokens["state"] in RelayStallStates || tokens["health"] in RelayStallStates) {
        evidence.add(RuntimeEvidenceCategory.RelayStall, event)
        return
    }
    if (level in WarningLevels && tokens["failure_class"] in RelayStallFailureClasses) {
        evidence.add(RuntimeEvidenceCategory.RelayStall, event)
    }
}

private fun collectDnsEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    level: String,
    evidence: RuntimeEvidenceAccumulator,
) {
    if (level !in WarningLevels) return
    val exactDnsFailure =
        tokens["event"] in DnsFailureEvents ||
            tokens["failure_class"] == "dns_interference" ||
            tokens["dns_failure"] == "true" ||
            tokens["dns_failures_total"]?.toLongOrNull()?.let { it > 0L } == true
    if (exactDnsFailure) {
        evidence.add(RuntimeEvidenceCategory.DnsFailure, event)
    }
}

private fun collectExplicitMtuEvidence(
    event: NativeSessionEventEntity,
    tokens: Map<String, String>,
    evidence: RuntimeEvidenceAccumulator,
) {
    val explicitPmtuEvidence =
        event.subsystem == "pmtu" ||
            tokens["pmtu_blackhole"] == "true" ||
            tokens["mtu_blackhole"] == "true" ||
            tokens["state"] == "pmtu_blackhole"
    if (explicitPmtuEvidence) {
        evidence.add(RuntimeEvidenceCategory.MtuBlackhole, event)
    }
}

private fun selectVerdict(evidence: RuntimeEvidenceAccumulator): RuntimeRootCauseVerdict =
    when {
        evidence.has(RuntimeEvidenceCategory.DataPlaneForwardingHealthy) -> RuntimeRootCauseVerdict.INCONCLUSIVE
        else -> selectDirectVerdict(evidence) ?: selectDataPlaneVerdict(evidence)
    }

private fun selectDirectVerdict(evidence: RuntimeEvidenceAccumulator): RuntimeRootCauseVerdict? {
    val directVerdicts =
        listOfNotNull(
            RuntimeRootCauseVerdict.OEM_PROCESS_KILL.takeIf {
                evidence.has(RuntimeEvidenceCategory.DeviceProcessKill)
            },
            RuntimeRootCauseVerdict.UNDERLAY_LOST.takeIf {
                evidence.has(RuntimeEvidenceCategory.UnderlayLost)
            },
            RuntimeRootCauseVerdict.DNS_FAILURE.takeIf {
                evidence.has(RuntimeEvidenceCategory.DnsFailure)
            },
            RuntimeRootCauseVerdict.MTU_BLACKHOLE.takeIf {
                evidence.has(RuntimeEvidenceCategory.MtuBlackhole)
            },
            RuntimeRootCauseVerdict.RELAY_STALL.takeIf {
                evidence.has(RuntimeEvidenceCategory.RelayStall)
            },
        )
    return when (directVerdicts.distinct().size) {
        0 -> null
        1 -> directVerdicts.single()
        else -> RuntimeRootCauseVerdict.INCONCLUSIVE
    }
}

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
    DeviceProcessKill("device_state_process_kill"),
    DataPlaneTunIngressNoUpstream("data_plane_tun_ingress_no_upstream"),
    DataPlaneOutboundNoReturn("data_plane_outbound_no_return"),
    DataPlaneForwardingHealthy("data_plane_forwarding_healthy"),
    DnsFailure("dns_failure"),
    MtuBlackhole("mtu_blackhole"),
    RelayStall("relay_stall"),
    ProtectFailure("protect_failure"),
}

private fun RuntimeRootCauseVerdict.evidenceCategories(): Set<RuntimeEvidenceCategory> =
    when (this) {
        RuntimeRootCauseVerdict.UNDERLAY_LOST -> {
            setOf(RuntimeEvidenceCategory.UnderlayLost)
        }

        RuntimeRootCauseVerdict.OEM_PROCESS_KILL -> {
            setOf(RuntimeEvidenceCategory.DeviceProcessKill)
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

private fun String.toKeyValueTokens(): Map<String, String> =
    split(' ', ';')
        .asSequence()
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return@mapNotNull null
            val key = token.substring(0, separator).lowercase(Locale.US)
            val value = token.substring(separator + 1).trim(',', '"').lowercase(Locale.US)
            key to value
        }.toMap()

private fun networkCapabilityMissing(
    validated: String?,
    internet: String?,
): Boolean = validated == "absent" || internet == "absent"

private const val MaxRuntimeRootCauseEvents = 64
private const val MaxRuntimeRootCauseEvidenceRefs = 8
private const val RuntimeRootCauseWindowMillis = 5 * 60 * 1000L

private val WarningLevels = setOf("warn", "error")
private val DnsFailureEvents = setOf("dns_failure", "dns_resolution_failed", "resolver_failure")
private val RelayStallStates = setOf("stalled", "frozen")
private val RelayStallFailureClasses = setOf("relay_stall", "timeout")
private val ForegroundFailureOutcomes =
    setOf("start_not_allowed", "security_rejected", "invalid_type", "other_failure")
private val ProcessKillReasons =
    setOf("low_memory", "excessive_resource_usage", "user_stopped", "dependency_died")
private val DataPlaneSupportedVerdicts =
    setOf(RuntimeRootCauseVerdict.VPN_ROUTE_LOOP, RuntimeRootCauseVerdict.VPN_PATH_LOSS)
