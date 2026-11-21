@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeNoDirectSolutionCooldownMs
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import com.poyka.ripdpi.data.normalizeStrategyFamilyToTcpFamily
import java.security.MessageDigest
import java.util.Locale

internal data class DirectModePolicyEvaluation(
    val authority: String,
    val envelope: TransportPolicyEnvelope,
    val verdict: DirectModeVerdict?,
)

private const val VerdictPriorityNoDirectSolution = 2
private const val VerdictPriorityTransparentWorks = 1

private const val DnsClassificationPriorityEchCapable = 4
private const val DnsClassificationPriorityPoisoned = 3
private const val DnsClassificationPriorityDivergent = 2

private const val IpSetDigestByteCount = 8
private val tlsAttemptPrefixes = listOf("tls13", "tls12", "tlsEch")

private data class DirectModeDnsPolicyObservation(
    val classification: DirectDnsClassification? = null,
    val dnsMode: DnsMode? = null,
)

internal fun deriveDirectModeVerdict(report: ScanReport): DirectModeVerdict? =
    report
        .takeIf(ScanReport::hasDefinitiveDirectModeEvidence)
        ?.let(::collectDirectModePolicyEvaluations)
        .orEmpty()
        .maxByOrNull { it.priority() }
        ?.verdict

internal fun enrichDirectPathCapabilityObservation(
    report: ScanReport,
    authority: String,
    observation: ServerCapabilityObservation,
): ServerCapabilityObservation =
    report
        .takeIf(ScanReport::hasDefinitiveDirectModeEvidence)
        ?.let {
            val evaluation =
                collectDirectModePolicyEvaluations(report)
                    .firstOrNull { it.authority == authority.normalizeDirectModeAuthority() }
            evaluation
                ?.let { policy ->
                    observation.copy(
                        transportPolicy = policy.envelope.policy,
                        ipSetDigest = policy.envelope.ipSetDigest,
                        dnsClassification = policy.envelope.dnsClassification,
                        transportClass = policy.envelope.transportClass,
                        reasonCode = policy.envelope.reasonCode,
                        cooldownUntil = policy.envelope.cooldownUntil,
                    )
                }
                ?: observation
        }
        ?: observation

private fun ScanReport.hasDefinitiveDirectModeEvidence(): Boolean =
    // Only a raw-path scan measures the direct path; an in-path scan ran through the active tunnel or proxy.
    pathMode == ScanPathMode.RAW_PATH &&
        completionKind == ScanCompletionKind.NORMAL &&
        terminationReason == null &&
        results.none { !it.isQuicInitialProbe() && it.invalidatesDirectModeEvidence() } &&
        (
            strategyProbeReport?.let { strategyProbe ->
                strategyProbe.completionKind == StrategyProbeCompletionKind.NORMAL &&
                    DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(strategyProbe) ==
                    DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible
            } ?: false
        )

private fun ProbeResult.invalidatesDirectModeEvidence(): Boolean =
    detailValue("postDeadline")?.toBooleanStrictOrNull() == true ||
        detailValue("sampleAfterDeadline")?.toBooleanStrictOrNull() == true ||
        detailValue("controlOutcome")?.contains("mixed", ignoreCase = true) == true ||
        detailValue("failureClass")?.contains("setup", ignoreCase = true) == true

private fun collectDirectModePolicyEvaluations(report: ScanReport): List<DirectModePolicyEvaluation> {
    val groupedResults =
        report
            .resultsForDirectModePolicy()
            .mapNotNull { result ->
                result.directModeAuthority()?.let { authority -> authority to result }
            }.groupBy({ it.first }, { it.second })
    return groupedResults.map { (authority, results) ->
        deriveDirectModePolicyEvaluation(authority, results, report.finishedAt)
    }
}

private data class DirectModeTransportSignals(
    val hasTransparentSuccess: Boolean,
    val hasTlsFailureWithoutResponse: Boolean,
    val hasTlsPostClientHelloFailure: Boolean,
    val allAttemptsFailed: Boolean,
    val noDirectTlsFailure: Boolean,
    val transportClass: DirectTransportClass?,
)

private fun deriveTransportSignals(results: List<ProbeResult>): DirectModeTransportSignals {
    val hasTransparentSuccess = results.any(ProbeResult::edgeSuccess)
    val hasTlsFailureWithoutResponse = results.any { it.hasTlsHandshakeFailureEvidence(peerResponseReceived = false) }
    val hasTlsPostClientHelloFailure =
        results.any { it.hasTlsHandshakeFailureEvidence(peerResponseReceived = true) }
    val allAttemptsFailed = results.isNotEmpty() && results.all(ProbeResult::isDirectModeFailure)
    val noDirectTlsFailure = allAttemptsFailed && hasTlsFailureWithoutResponse
    return DirectModeTransportSignals(
        hasTransparentSuccess = hasTransparentSuccess,
        hasTlsFailureWithoutResponse = hasTlsFailureWithoutResponse,
        hasTlsPostClientHelloFailure = hasTlsPostClientHelloFailure,
        allAttemptsFailed = allAttemptsFailed,
        noDirectTlsFailure = noDirectTlsFailure,
        transportClass = DirectTransportClass.SNI_TLS_SUSPECT.takeIf { hasTlsFailureWithoutResponse },
    )
}

private fun deriveTransportPolicy(
    signals: DirectModeTransportSignals,
    dnsMode: DnsMode?,
): TransportPolicy {
    val base =
        when {
            signals.allAttemptsFailed -> {
                TransportPolicy(
                    quicMode = if (signals.noDirectTlsFailure) QuicMode.HARD_DISABLE else QuicMode.ALLOW,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily =
                        if (signals.noDirectTlsFailure) {
                            normalizeStrategyFamilyToTcpFamily("tlsrec")
                        } else {
                            TcpFamily.NONE
                        },
                    outcome = DirectModeOutcome.NO_DIRECT_SOLUTION,
                )
            }

            signals.hasTlsFailureWithoutResponse -> {
                TransportPolicy(
                    quicMode = QuicMode.HARD_DISABLE,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily = normalizeStrategyFamilyToTcpFamily("tlsrec"),
                    outcome = DirectModeOutcome.TRANSPARENT_OK,
                )
            }

            else -> {
                TransportPolicy()
            }
        }
    return dnsMode?.let { base.copy(dnsMode = it) } ?: base
}

private fun deriveReasonCode(signals: DirectModeTransportSignals): DirectModeReasonCode? =
    when {
        signals.hasTlsPostClientHelloFailure -> DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
        signals.noDirectTlsFailure -> DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE
        signals.hasTlsFailureWithoutResponse -> DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE
        signals.hasTransparentSuccess -> null
        else -> DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE
    }

private fun buildVerdict(
    outcome: DirectModeOutcome,
    signals: DirectModeTransportSignals,
    reasonCode: DirectModeReasonCode?,
    authority: String,
    cooldownUntil: Long?,
): DirectModeVerdict? =
    when (outcome) {
        DirectModeOutcome.TRANSPARENT_OK -> {
            // A TLS failure alone proves neither claim: a local strategy may still work, so emit no verdict.
            if (signals.hasTransparentSuccess) {
                DirectModeVerdict(
                    result = DirectModeVerdictResult.TRANSPARENT_WORKS,
                    reasonCode = reasonCode,
                    transportClass = signals.transportClass,
                    authority = authority,
                    cooldownUntil = cooldownUntil,
                )
            } else {
                null
            }
        }

        DirectModeOutcome.OWNED_STACK_ONLY -> {
            null
        }

        DirectModeOutcome.NO_DIRECT_SOLUTION -> {
            DirectModeVerdict(
                result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                reasonCode = reasonCode,
                transportClass = signals.transportClass,
                authority = authority,
                cooldownUntil = cooldownUntil,
            )
        }
    }

private fun deriveDirectModePolicyEvaluation(
    authority: String,
    results: List<ProbeResult>,
    finishedAt: Long,
): DirectModePolicyEvaluation {
    val dnsObservation = deriveDnsPolicyObservation(results)
    val signals = deriveTransportSignals(results)
    val policy = deriveTransportPolicy(signals, dnsObservation.dnsMode)
    val reasonCode = deriveReasonCode(signals)
    val envelope =
        TransportPolicyEnvelope(
            policy = policy,
            ipSetDigest = deriveIpSetDigest(results),
            dnsClassification = dnsObservation.classification,
            transportClass = signals.transportClass,
            reasonCode = reasonCode,
            cooldownUntil =
                if (policy.outcome == DirectModeOutcome.NO_DIRECT_SOLUTION) {
                    finishedAt + DirectModeNoDirectSolutionCooldownMs
                } else {
                    null
                },
        )
    val verdict = buildVerdict(policy.outcome, signals, reasonCode, authority, envelope.cooldownUntil)
    return DirectModePolicyEvaluation(
        authority = authority,
        envelope = envelope,
        verdict = verdict,
    )
}

private fun DirectModePolicyEvaluation.priority(): Int =
    when (verdict?.result) {
        DirectModeVerdictResult.OWNED_STACK_ONLY, null -> 0
        DirectModeVerdictResult.NO_DIRECT_SOLUTION -> VerdictPriorityNoDirectSolution
        DirectModeVerdictResult.TRANSPARENT_WORKS -> VerdictPriorityTransparentWorks
    }

private fun deriveDnsPolicyObservation(results: List<ProbeResult>): DirectModeDnsPolicyObservation {
    val dnsResults = results.filter { it.probeType == "dns_integrity" }
    if (dnsResults.isEmpty()) {
        return DirectModeDnsPolicyObservation()
    }
    val classification =
        dnsResults
            .asSequence()
            .mapNotNull { result -> result.detailValue("dnsClassification")?.toDirectDnsClassificationOrNull() }
            .maxByOrNull(::dnsClassificationPriority)
    val answerClass =
        dnsResults
            .asSequence()
            .mapNotNull { result -> result.detailValue("dnsAnswerClass")?.trim()?.uppercase(Locale.US) }
            .firstOrNull { it == "POISONED" }
    val selectedResolverRole =
        dnsResults
            .asSequence()
            .mapNotNull { result -> result.detailValue("dnsSelectedResolverRole")?.trim()?.lowercase(Locale.US) }
            .firstOrNull()
    val dnsMode =
        if (answerClass == "POISONED") {
            when (selectedResolverRole) {
                "primary" -> DnsMode.DOH_PRIMARY
                "secondary" -> DnsMode.DOH_SECONDARY
                else -> null
            }
        } else {
            null
        }
    return DirectModeDnsPolicyObservation(
        classification = classification,
        dnsMode = dnsMode,
    )
}

private fun String.toDirectDnsClassificationOrNull(): DirectDnsClassification? =
    runCatching { DirectDnsClassification.valueOf(trim().uppercase(Locale.US)) }.getOrNull()

private fun dnsClassificationPriority(classification: DirectDnsClassification): Int =
    when (classification) {
        DirectDnsClassification.ECH_CAPABLE -> DnsClassificationPriorityEchCapable
        DirectDnsClassification.POISONED -> DnsClassificationPriorityPoisoned
        DirectDnsClassification.DIVERGENT -> DnsClassificationPriorityDivergent
        DirectDnsClassification.CLEAN -> 1
        DirectDnsClassification.NO_HTTPS_RR -> 0
    }

private fun ProbeResult.directModeAuthority(): String? =
    (
        detailValue("targetHost")
            ?: detailValue("handshakeHost")
            ?: detailValue("quicHost")
            ?: inferEdgeHost()
    )?.normalizeDirectModeAuthority()

private fun ProbeResult.isDirectModeFailure(): Boolean =
    when {
        edgeSuccess() -> false
        outcome in setOf("tls_handshake_failed", "unreachable", "http_unreachable") -> true
        outcome.contains("blocked", ignoreCase = true) -> true
        outcome.contains("unreachable", ignoreCase = true) -> true
        else -> false
    }

private fun ProbeResult.hasTlsHandshakeFailureEvidence(peerResponseReceived: Boolean): Boolean =
    tlsAttemptPrefixes.any { prefix ->
        // An alert of unknown origin is evidence of a response only.
        detailValue("${prefix}Status") == "tls_handshake_failed" &&
            detailValue("${prefix}FailureStage") == "tls_handshake" &&
            detailValue("${prefix}PeerResponseReceived")?.toBooleanStrictOrNull() == peerResponseReceived &&
            (peerResponseReceived || detailValue("${prefix}AlertCode") == null)
    }

private fun deriveIpSetDigest(results: List<ProbeResult>): String {
    val connectedIps =
        results
            .mapNotNull { it.detailValue("connectedIp")?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .sorted()
    if (connectedIps.isEmpty()) {
        return ""
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(connectedIps.joinToString(",").toByteArray())
    return digest.take(IpSetDigestByteCount).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun String.normalizeDirectModeAuthority(): String = trim().trimEnd('.').lowercase(Locale.US)
