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

private const val VerdictPriorityOwnedStack = 3
private const val VerdictPriorityNoDirectSolution = 2
private const val VerdictPriorityTransparentWorks = 1

private const val DnsClassificationPriorityEchCapable = 4
private const val DnsClassificationPriorityPoisoned = 3
private const val DnsClassificationPriorityDivergent = 2

private const val IpSetDigestByteCount = 8

private data class DirectModeDnsPolicyObservation(
    val classification: DirectDnsClassification? = null,
    val dnsMode: DnsMode? = null,
)

internal fun deriveDirectModeVerdict(report: ScanReport): DirectModeVerdict? =
    collectDirectModePolicyEvaluations(report)
        .maxByOrNull { it.priority() }
        ?.verdict

internal fun enrichDirectPathCapabilityObservation(
    report: ScanReport,
    authority: String,
    observation: ServerCapabilityObservation,
): ServerCapabilityObservation {
    val evaluation =
        collectDirectModePolicyEvaluations(report)
            .firstOrNull { it.authority == authority.normalizeDirectModeAuthority() }
            ?: return observation
    return observation.copy(
        transportPolicy = evaluation.envelope.policy,
        ipSetDigest = evaluation.envelope.ipSetDigest,
        dnsClassification = evaluation.envelope.dnsClassification,
        transportClass = evaluation.envelope.transportClass,
        reasonCode = evaluation.envelope.reasonCode,
        cooldownUntil = evaluation.envelope.cooldownUntil,
    )
}

private fun collectDirectModePolicyEvaluations(report: ScanReport): List<DirectModePolicyEvaluation> {
    val groupedResults =
        report.results
            .mapNotNull { result ->
                result.directModeAuthority()?.let { authority -> authority to result }
            }.groupBy({ it.first }, { it.second })
    return groupedResults.map { (authority, results) ->
        deriveDirectModePolicyEvaluation(authority, results, report.finishedAt)
    }
}

private data class DirectModeTransportSignals(
    val hasTransparentSuccess: Boolean,
    val hasQuicBlocked: Boolean,
    val hasTlsPostClientHelloFailure: Boolean,
    val hasOwnedStackOnly: Boolean,
    val allAttemptsFailed: Boolean,
    val noDirectTlsFailure: Boolean,
    val noDirectQuicFailure: Boolean,
    val noDirectIpFailure: Boolean,
    val transportClass: DirectTransportClass?,
)

private fun deriveTransportSignals(results: List<ProbeResult>): DirectModeTransportSignals {
    val hasTransparentSuccess = results.any(ProbeResult::edgeSuccess)
    val hasQuicBlocked =
        results.any { result ->
            (result.probeType == "strategy_quic" || result.probeType == "quic_reachability") &&
                result.outcome == "quic_error"
        }
    val hasTlsPostClientHelloFailure =
        results.any { result ->
            result.outcome == "tls_handshake_failed" ||
                result
                    .detailValue("failureClass")
                    ?.trim()
                    ?.lowercase(Locale.US)
                    ?.contains("tls") == true
        }
    val hasOwnedStackOnly = results.any { it.outcome == "tls_ech_only" }
    val allAttemptsFailed = results.isNotEmpty() && results.all(ProbeResult::isDirectModeFailure)
    val noDirectTlsFailure = allAttemptsFailed && (hasOwnedStackOnly || hasTlsPostClientHelloFailure)
    val noDirectQuicFailure = allAttemptsFailed && hasQuicBlocked && !noDirectTlsFailure
    val noDirectIpFailure = allAttemptsFailed && !noDirectTlsFailure && !noDirectQuicFailure
    val transportClass =
        when {
            hasOwnedStackOnly || hasTlsPostClientHelloFailure -> DirectTransportClass.SNI_TLS_SUSPECT
            hasQuicBlocked -> DirectTransportClass.QUIC_BLOCK_SUSPECT
            noDirectIpFailure -> DirectTransportClass.IP_BLOCK_SUSPECT
            else -> null
        }
    return DirectModeTransportSignals(
        hasTransparentSuccess = hasTransparentSuccess,
        hasQuicBlocked = hasQuicBlocked,
        hasTlsPostClientHelloFailure = hasTlsPostClientHelloFailure,
        hasOwnedStackOnly = hasOwnedStackOnly,
        allAttemptsFailed = allAttemptsFailed,
        noDirectTlsFailure = noDirectTlsFailure,
        noDirectQuicFailure = noDirectQuicFailure,
        noDirectIpFailure = noDirectIpFailure,
        transportClass = transportClass,
    )
}

private fun deriveTransportPolicy(
    signals: DirectModeTransportSignals,
    dnsMode: DnsMode?,
): TransportPolicy {
    val base =
        when {
            signals.hasOwnedStackOnly -> {
                TransportPolicy(
                    quicMode = if (signals.hasQuicBlocked) QuicMode.HARD_DISABLE else QuicMode.ALLOW,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily =
                        if (signals.hasTlsPostClientHelloFailure) {
                            normalizeStrategyFamilyToTcpFamily("tlsrec")
                        } else {
                            TcpFamily.NONE
                        },
                    outcome = DirectModeOutcome.OWNED_STACK_ONLY,
                )
            }

            signals.allAttemptsFailed -> {
                TransportPolicy(
                    quicMode =
                        when {
                            signals.noDirectTlsFailure -> QuicMode.HARD_DISABLE
                            signals.noDirectQuicFailure -> QuicMode.SOFT_DISABLE
                            else -> QuicMode.ALLOW
                        },
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

            signals.hasTlsPostClientHelloFailure -> {
                TransportPolicy(
                    quicMode = QuicMode.HARD_DISABLE,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily = normalizeStrategyFamilyToTcpFamily("tlsrec"),
                    outcome = DirectModeOutcome.TRANSPARENT_OK,
                )
            }

            signals.hasQuicBlocked -> {
                TransportPolicy(
                    quicMode = QuicMode.SOFT_DISABLE,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily = TcpFamily.NONE,
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
        signals.hasOwnedStackOnly -> DirectModeReasonCode.OWNED_STACK_REQUIRED
        signals.noDirectTlsFailure -> DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
        signals.noDirectQuicFailure -> DirectModeReasonCode.QUIC_BLOCKED
        signals.noDirectIpFailure -> DirectModeReasonCode.IP_BLOCKED
        signals.hasTlsPostClientHelloFailure -> DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
        signals.hasQuicBlocked -> DirectModeReasonCode.QUIC_BLOCKED
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
            if (signals.hasTransparentSuccess || signals.hasQuicBlocked || signals.hasTlsPostClientHelloFailure) {
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
            DirectModeVerdict(
                result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                reasonCode = reasonCode,
                transportClass = signals.transportClass,
                authority = authority,
                cooldownUntil = cooldownUntil,
            )
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
        DirectModeVerdictResult.OWNED_STACK_ONLY -> VerdictPriorityOwnedStack
        DirectModeVerdictResult.NO_DIRECT_SOLUTION -> VerdictPriorityNoDirectSolution
        DirectModeVerdictResult.TRANSPARENT_WORKS -> VerdictPriorityTransparentWorks
        null -> 0
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
        outcome == "tls_ech_only" -> false
        edgeSuccess() -> false
        outcome in setOf("quic_error", "tls_handshake_failed", "unreachable", "http_unreachable") -> true
        outcome.contains("blocked", ignoreCase = true) -> true
        outcome.contains("unreachable", ignoreCase = true) -> true
        else -> false
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
