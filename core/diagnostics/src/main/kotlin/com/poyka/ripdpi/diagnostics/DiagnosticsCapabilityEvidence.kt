package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import java.util.Locale

private const val CapabilitySummaryItemLimit = 3

data class DiagnosticsCapabilityEvidence(
    val authority: String,
    val summary: String,
    val details: List<DiagnosticsAppliedSetting> = emptyList(),
    val source: String = "diagnostics",
    val updatedAt: Long = 0L,
)

internal fun summarizeCapabilityEvidence(
    records: List<ServerCapabilityRecord>,
    relevantAuthorities: Set<String> = emptySet(),
    limit: Int = 4,
): List<DiagnosticsCapabilityEvidence> {
    val normalizedAuthorities = relevantAuthorities.map(String::normalizeCapabilityHost).toSet()
    return records
        .asSequence()
        .filter { record ->
            normalizedAuthorities.isEmpty() || record.authority.normalizeCapabilityHost() in normalizedAuthorities
        }.groupBy { it.authority.trim() }
        .values
        .map { recordsForAuthority ->
            val sorted = recordsForAuthority.sortedByDescending(ServerCapabilityRecord::updatedAt)
            val merged = mergeCapabilityRecords(sorted)
            DiagnosticsCapabilityEvidence(
                authority = merged.authority,
                summary = capabilitySummaryLine(merged),
                details = capabilityDetailFields(merged),
                source = merged.source,
                updatedAt = merged.updatedAt,
            )
        }.sortedByDescending(DiagnosticsCapabilityEvidence::updatedAt)
        .take(limit)
}

internal fun collectDirectPathCapabilityObservations(report: ScanReport): Map<String, ServerCapabilityObservation> {
    val aggregated = linkedMapOf<String, ServerCapabilityObservation>()
    report.results.forEach { result ->
        val authority = result.directPathCapabilityAuthority() ?: return@forEach
        val observation = result.directPathCapabilityObservation()
        if (observation.isEmpty()) {
            return@forEach
        }
        aggregated[authority] = mergeObservation(aggregated[authority], observation)
    }
    report.observations.forEach { observation ->
        val authority = observation.directPathCapabilityAuthority() ?: return@forEach
        val derived = observation.directPathCapabilityObservation()
        if (derived.isEmpty()) {
            return@forEach
        }
        aggregated[authority] = mergeObservation(aggregated[authority], derived)
    }
    return aggregated.mapValues { (authority, observation) ->
        enrichDirectPathCapabilityObservation(report, authority, observation)
    }
}

private fun mergeCapabilityRecords(records: List<ServerCapabilityRecord>): ServerCapabilityRecord {
    val authority = records.first().authority
    val fingerprintHash = records.first().fingerprintHash
    val relayProfileId = records.firstOrNull()?.relayProfileId
    return ServerCapabilityRecord(
        scope = records.first().scope,
        fingerprintHash = fingerprintHash,
        authority = authority,
        relayProfileId = relayProfileId,
        quicUsable = records.reduceCapabilityFlag(ServerCapabilityRecord::quicUsable),
        udpUsable = records.reduceCapabilityFlag(ServerCapabilityRecord::udpUsable),
        authModeAccepted = records.reduceCapabilityFlag(ServerCapabilityRecord::authModeAccepted),
        multiplexReusable = records.reduceCapabilityFlag(ServerCapabilityRecord::multiplexReusable),
        shadowTlsCamouflageAccepted =
            records.reduceCapabilityFlag(ServerCapabilityRecord::shadowTlsCamouflageAccepted),
        naiveHttpsProxyAccepted =
            records.reduceCapabilityFlag(ServerCapabilityRecord::naiveHttpsProxyAccepted),
        fallbackRequired = records.reduceCapabilityFlag(ServerCapabilityRecord::fallbackRequired),
        repeatedHandshakeFailureClass =
            records
                .mapNotNull(ServerCapabilityRecord::repeatedHandshakeFailureClass)
                .firstOrNull { it.isNotBlank() },
        transportPolicyEnvelope =
            records
                .sortedByDescending(ServerCapabilityRecord::updatedAt)
                .firstNotNullOfOrNull { it.transportPolicyEnvelope ?: it.effectiveTransportPolicyEnvelope() },
        source = records.first().source,
        updatedAt = records.maxOf(ServerCapabilityRecord::updatedAt),
    )
}

private fun capabilitySummaryLine(record: ServerCapabilityRecord): String =
    buildList {
        record.transportPolicyEnvelope?.policy?.outcome?.let { outcome ->
            when (outcome) {
                DirectModeOutcome.OWNED_STACK_ONLY -> add("Owned stack required")
                DirectModeOutcome.NO_DIRECT_SOLUTION -> add("No direct solution")
                DirectModeOutcome.TRANSPARENT_OK -> Unit
            }
        }
        record.transportPolicyEnvelope?.dnsClassification?.let { add("DNS ${it.name.lowercase(Locale.US)}") }
        record.quicUsable?.let { add(if (it) "QUIC usable" else "QUIC blocked") }
        record.udpUsable?.let { add(if (it) "UDP usable" else "UDP blocked") }
        record.authModeAccepted?.let { add(if (it) "Auth accepted" else "Auth rejected") }
        record.multiplexReusable?.let { add(if (it) "Multiplex reusable" else "Multiplex limited") }
        record.shadowTlsCamouflageAccepted?.let {
            add(if (it) "ShadowTLS accepted" else "ShadowTLS rejected")
        }
        record.naiveHttpsProxyAccepted?.let {
            add(if (it) "HTTPS proxy accepted" else "HTTPS proxy rejected")
        }
        if (record.fallbackRequired == true) {
            add("Fallback required")
        }
        record.repeatedHandshakeFailureClass?.let { add("Failure $it") }
    }.take(CapabilitySummaryItemLimit)
        .joinToString(separator = " · ")
        .ifBlank { "Capability evidence recorded for this authority." }

private fun capabilityDetailFields(record: ServerCapabilityRecord): List<DiagnosticsAppliedSetting> =
    buildList {
        record.transportPolicyEnvelope?.let { envelope ->
            add(DiagnosticsAppliedSetting("Policy", envelope.policy.outcome.name))
            envelope.dnsClassification?.let { add(DiagnosticsAppliedSetting("DNS class", it.name)) }
            add(DiagnosticsAppliedSetting("QUIC mode", envelope.policy.quicMode.name))
            add(DiagnosticsAppliedSetting("Preferred stack", envelope.policy.preferredStack.name))
            add(DiagnosticsAppliedSetting("TCP family", envelope.policy.tcpFamily.name))
            envelope.transportClass?.let { add(DiagnosticsAppliedSetting("Transport class", it.name)) }
            envelope.reasonCode?.let { add(DiagnosticsAppliedSetting("Reason", it.name)) }
        }
        record.quicUsable?.let { add(DiagnosticsAppliedSetting("QUIC", capabilityFlagLabel(it))) }
        record.udpUsable?.let { add(DiagnosticsAppliedSetting("UDP", capabilityFlagLabel(it))) }
        record.authModeAccepted?.let { add(DiagnosticsAppliedSetting("Auth", capabilityFlagLabel(it))) }
        record.multiplexReusable?.let {
            add(DiagnosticsAppliedSetting("Multiplex", capabilityFlagLabel(it)))
        }
        record.shadowTlsCamouflageAccepted?.let {
            add(DiagnosticsAppliedSetting("ShadowTLS", capabilityFlagLabel(it)))
        }
        record.naiveHttpsProxyAccepted?.let {
            add(DiagnosticsAppliedSetting("HTTPS proxy", capabilityFlagLabel(it)))
        }
        record.fallbackRequired?.let {
            add(
                DiagnosticsAppliedSetting(
                    "Fallback",
                    if (it) {
                        "Required"
                    } else {
                        "Not required"
                    },
                ),
            )
        }
        record.repeatedHandshakeFailureClass?.let {
            add(DiagnosticsAppliedSetting("Last failure", it))
        }
    }

private fun capabilityFlagLabel(value: Boolean): String = if (value) "Available" else "Blocked"

private fun mergeObservation(
    existing: ServerCapabilityObservation?,
    incoming: ServerCapabilityObservation,
): ServerCapabilityObservation =
    ServerCapabilityObservation(
        quicUsable = incoming.quicUsable ?: existing?.quicUsable,
        udpUsable = incoming.udpUsable ?: existing?.udpUsable,
        authModeAccepted = incoming.authModeAccepted ?: existing?.authModeAccepted,
        multiplexReusable = incoming.multiplexReusable ?: existing?.multiplexReusable,
        shadowTlsCamouflageAccepted =
            incoming.shadowTlsCamouflageAccepted ?: existing?.shadowTlsCamouflageAccepted,
        naiveHttpsProxyAccepted =
            incoming.naiveHttpsProxyAccepted ?: existing?.naiveHttpsProxyAccepted,
        fallbackRequired = incoming.fallbackRequired ?: existing?.fallbackRequired,
        repeatedHandshakeFailureClass =
            incoming.repeatedHandshakeFailureClass ?: existing?.repeatedHandshakeFailureClass,
        transportPolicy = incoming.transportPolicy ?: existing?.transportPolicy,
        ipSetDigest = incoming.ipSetDigest ?: existing?.ipSetDigest,
        dnsClassification = incoming.dnsClassification ?: existing?.dnsClassification,
        transportClass = incoming.transportClass ?: existing?.transportClass,
        reasonCode = incoming.reasonCode ?: existing?.reasonCode,
        cooldownUntil = incoming.cooldownUntil ?: existing?.cooldownUntil,
    )

private fun ServerCapabilityObservation.isEmpty(): Boolean =
    quicUsable == null &&
        udpUsable == null &&
        authModeAccepted == null &&
        multiplexReusable == null &&
        shadowTlsCamouflageAccepted == null &&
        naiveHttpsProxyAccepted == null &&
        fallbackRequired == null &&
        repeatedHandshakeFailureClass == null &&
        transportPolicy == null &&
        ipSetDigest == null &&
        dnsClassification == null &&
        transportClass == null &&
        reasonCode == null &&
        cooldownUntil == null

private fun ProbeResult.directPathCapabilityAuthority(): String? =
    detailValue("targetHost")
        ?: detailValue("handshakeHost")
        ?: detailValue("quicHost")
        ?: inferEdgeHost()

private fun ProbeResult.directPathCapabilityObservation(): ServerCapabilityObservation =
    when (probeType) {
        "strategy_quic", "quic_reachability" -> {
            val success = edgeSuccess()
            ServerCapabilityObservation(
                quicUsable = success,
                udpUsable = success,
            )
        }

        "strategy_failure_classification" -> {
            val fallbackDecision = detailValue("fallbackDecision")?.trim()?.lowercase(Locale.US)
            ServerCapabilityObservation(
                fallbackRequired =
                    when (fallbackDecision) {
                        "retry_with_matching_group", "resolver_override_recommended" -> true
                        "surface_only", "diagnostics_only", "none" -> false
                        else -> null
                    },
                repeatedHandshakeFailureClass =
                    detailValue("failureClass")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: outcome.trim().takeIf { it.isNotEmpty() },
            )
        }

        "dns_integrity" -> {
            ServerCapabilityObservation(
                dnsClassification = detailValue("dnsClassification")?.toDirectDnsClassificationOrNull(),
            )
        }

        else -> {
            ServerCapabilityObservation()
        }
    }

private fun ObservationFact.directPathCapabilityAuthority(): String? =
    target
        .substringAfterLast(" · ", missingDelimiterValue = target)
        .substringBefore(' ')
        .trim()
        .takeIf { it.isNotEmpty() }

private fun ObservationFact.directPathCapabilityObservation(): ServerCapabilityObservation =
    when {
        quic != null -> {
            val usable = quic.status == QuicProbeStatus.INITIAL_RESPONSE || quic.status == QuicProbeStatus.RESPONSE
            ServerCapabilityObservation(
                quicUsable = usable,
                udpUsable = usable,
            )
        }

        strategy != null &&
            strategy.protocol == StrategyProbeProtocol.QUIC &&
            strategy.status != StrategyProbeStatus.NOT_APPLICABLE
        -> {
            val usable = strategy.status == StrategyProbeStatus.SUCCESS
            ServerCapabilityObservation(
                quicUsable = usable,
                udpUsable = usable,
            )
        }

        else -> {
            ServerCapabilityObservation()
        }
    }

private fun List<ServerCapabilityRecord>.reduceCapabilityFlag(
    selector: (ServerCapabilityRecord) -> Boolean?,
): Boolean? =
    when {
        any { selector(it) == true } -> true
        any { selector(it) == false } -> false
        else -> null
    }

private fun String.normalizeCapabilityHost(): String = substringBefore(':').trim().lowercase(Locale.US)

private fun String.toDirectDnsClassificationOrNull(): DirectDnsClassification? =
    runCatching { DirectDnsClassification.valueOf(trim().uppercase(Locale.US)) }.getOrNull()
