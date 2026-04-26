@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.TransportPolicy
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import com.poyka.ripdpi.data.hasConfirmedDirectPolicy
import java.util.Locale

private data class DirectModePersistenceSignals(
    val hasDnsSignal: Boolean,
    val activeAttemptCount: Int,
    val activeFailureCount: Int,
)

internal fun buildPersistableDirectPathObservations(
    report: ScanReport,
    existingRecords: List<ServerCapabilityRecord>,
): List<Pair<String, ServerCapabilityObservation>> {
    val recordsByAuthority =
        existingRecords.groupBy { it.authority.normalizePersistedAuthority() }
    return collectDirectPathCapabilityObservations(report).map { (authority, observation) ->
        val normalizedAuthority = authority.normalizePersistedAuthority()
        val existing =
            recordsByAuthority[normalizedAuthority]
                .orEmpty()
                .bestMatchingRecord(observation)
        authority to persistableObservation(report, normalizedAuthority, observation, existing)
    }
}

private fun persistableObservation(
    report: ScanReport,
    authority: String,
    observation: ServerCapabilityObservation,
    existing: ServerCapabilityRecord?,
): ServerCapabilityObservation {
    val policy = observation.transportPolicy ?: return observation
    return resolvedObservation(report, authority, observation, existing, policy)
}

private fun resolvedObservation(
    report: ScanReport,
    authority: String,
    observation: ServerCapabilityObservation,
    existing: ServerCapabilityRecord?,
    policy: TransportPolicy,
): ServerCapabilityObservation {
    val currentEnvelope = observation.toEnvelope()
    val existingEnvelope = existing?.effectiveTransportPolicyEnvelope()
    val existingCompatible =
        existing != null &&
            existing.hasConfirmedDirectPolicy() &&
            existingEnvelope != null &&
            envelopeSignature(existingEnvelope) == envelopeSignature(currentEnvelope)
    val signals = report.persistenceSignalsFor(authority)
    val confirmed =
        when (policy.outcome) {
            DirectModeOutcome.TRANSPARENT_OK,
            DirectModeOutcome.OWNED_STACK_ONLY,
            -> {
                existingCompatible || signals.activeAttemptCount >= 2 ||
                    (signals.activeAttemptCount >= 1 && signals.hasDnsSignal)
            }

            DirectModeOutcome.NO_DIRECT_SOLUTION -> {
                existingCompatible || signals.activeFailureCount >= 2
            }
        }
    return when {
        confirmed -> {
            observation.copy(
                policyConfirmedAt = existing?.policyConfirmedAt ?: report.finishedAt,
                policyFailureCount = 0,
            )
        }

        existing?.hasConfirmedDirectPolicy() == true && existingEnvelope != null && signals.activeFailureCount > 0 -> {
            ServerCapabilityObservation(
                ipSetDigest = existingEnvelope.ipSetDigest,
                policyFailureCount = existing.policyFailureCount + 1,
            )
        }

        else -> {
            observation.copy(
                transportPolicy = null,
                transportClass = null,
                reasonCode = null,
                cooldownUntil = null,
                policyConfirmedAt = null,
                policyFailureCount = existing?.policyFailureCount ?: 0,
            )
        }
    }
}

private fun List<ServerCapabilityRecord>.bestMatchingRecord(
    observation: ServerCapabilityObservation,
): ServerCapabilityRecord? {
    val digest = observation.ipSetDigest?.normalizeIpSetDigest().orEmpty()
    return firstOrNull {
        it.effectiveTransportPolicyEnvelope().ipSetDigest.normalizeIpSetDigest() == digest
    } ?: firstOrNull()
}

private fun ScanReport.persistenceSignalsFor(authority: String): DirectModePersistenceSignals {
    val matchingResults =
        results.filter { result ->
            result.directPathPersistenceAuthority()?.normalizePersistedAuthority() == authority
        }
    val activeResults = matchingResults.filter { it.probeType != "dns_integrity" }
    return DirectModePersistenceSignals(
        hasDnsSignal = matchingResults.any { it.probeType == "dns_integrity" },
        activeAttemptCount = activeResults.size,
        activeFailureCount = activeResults.count { it.isDirectFailureForPersistence() },
    )
}

private fun ServerCapabilityObservation.toEnvelope(): TransportPolicyEnvelope =
    TransportPolicyEnvelope(
        policy = requireNotNull(transportPolicy),
        ipSetDigest = ipSetDigest?.trim().orEmpty(),
        dnsClassification = dnsClassification,
        transportClass = transportClass,
        reasonCode = reasonCode,
        cooldownUntil = cooldownUntil,
    )

private fun envelopeSignature(envelope: TransportPolicyEnvelope): String =
    buildString {
        append(envelope.policy.outcome.name)
        append('|')
        append(envelope.policy.quicMode.name)
        append('|')
        append(envelope.policy.preferredStack.name)
        append('|')
        append(envelope.policy.dnsMode.name)
        append('|')
        append(envelope.policy.tcpFamily.name)
        append('|')
        append(envelope.transportClass?.name.orEmpty())
        append('|')
        append(envelope.reasonCode?.name.orEmpty())
        append('|')
        append(envelope.ipSetDigest.normalizeIpSetDigest())
    }

private fun String.normalizePersistedAuthority(): String = trim().trimEnd('.').lowercase(Locale.US)

private fun String.normalizeIpSetDigest(): String = trim().lowercase(Locale.US)

private fun ProbeResult.directPathPersistenceAuthority(): String? =
    detailValue("targetHost")
        ?: detailValue("handshakeHost")
        ?: detailValue("quicHost")
        ?: inferEdgeHost()

private fun ProbeResult.isDirectFailureForPersistence(): Boolean =
    when {
        outcome == "tls_ech_only" -> false
        edgeSuccess() -> false
        outcome in setOf("quic_error", "tls_handshake_failed", "unreachable", "http_unreachable") -> true
        outcome.contains("blocked", ignoreCase = true) -> true
        outcome.contains("unreachable", ignoreCase = true) -> true
        else -> false
    }
