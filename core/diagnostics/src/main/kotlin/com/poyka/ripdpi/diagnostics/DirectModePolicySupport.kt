@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

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

private fun deriveDirectModePolicyEvaluation(
    authority: String,
    results: List<ProbeResult>,
    finishedAt: Long,
): DirectModePolicyEvaluation {
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
    val transportClass =
        when {
            allAttemptsFailed -> DirectTransportClass.IP_BLOCK_SUSPECT
            hasTlsPostClientHelloFailure || hasOwnedStackOnly -> DirectTransportClass.SNI_TLS_SUSPECT
            hasQuicBlocked -> DirectTransportClass.QUIC_BLOCK_SUSPECT
            else -> null
        }
    val policy =
        when {
            hasOwnedStackOnly -> {
                TransportPolicy(
                    quicMode = if (hasQuicBlocked) QuicMode.HARD_DISABLE else QuicMode.ALLOW,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily =
                        if (hasTlsPostClientHelloFailure) {
                            normalizeStrategyFamilyToTcpFamily(
                                "tlsrec",
                            )
                        } else {
                            TcpFamily.NONE
                        },
                    outcome = DirectModeOutcome.OWNED_STACK_ONLY,
                )
            }

            allAttemptsFailed -> {
                TransportPolicy(
                    quicMode =
                        when {
                            hasTlsPostClientHelloFailure -> QuicMode.HARD_DISABLE
                            hasQuicBlocked -> QuicMode.SOFT_DISABLE
                            else -> QuicMode.ALLOW
                        },
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily =
                        if (hasTlsPostClientHelloFailure) {
                            normalizeStrategyFamilyToTcpFamily(
                                "tlsrec",
                            )
                        } else {
                            TcpFamily.NONE
                        },
                    outcome = DirectModeOutcome.NO_DIRECT_SOLUTION,
                )
            }

            hasTlsPostClientHelloFailure -> {
                TransportPolicy(
                    quicMode = QuicMode.HARD_DISABLE,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily = normalizeStrategyFamilyToTcpFamily("tlsrec"),
                    outcome = DirectModeOutcome.TRANSPARENT_OK,
                )
            }

            hasQuicBlocked -> {
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
    val reasonCode =
        when {
            hasOwnedStackOnly -> DirectModeReasonCode.OWNED_STACK_REQUIRED
            allAttemptsFailed -> DirectModeReasonCode.IP_BLOCKED
            hasTlsPostClientHelloFailure -> DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
            hasQuicBlocked -> DirectModeReasonCode.QUIC_BLOCKED
            hasTransparentSuccess -> null
            else -> DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE
        }
    val envelope =
        TransportPolicyEnvelope(
            policy = policy,
            ipSetDigest = deriveIpSetDigest(results),
            transportClass = transportClass,
            reasonCode = reasonCode,
            cooldownUntil =
                if (policy.outcome == DirectModeOutcome.NO_DIRECT_SOLUTION) {
                    finishedAt + DirectModeNoDirectSolutionCooldownMs
                } else {
                    null
                },
        )
    val verdict =
        when (policy.outcome) {
            DirectModeOutcome.TRANSPARENT_OK -> {
                if (hasTransparentSuccess || hasQuicBlocked || hasTlsPostClientHelloFailure) {
                    DirectModeVerdict(
                        result = DirectModeVerdictResult.TRANSPARENT_WORKS,
                        reasonCode = reasonCode,
                        transportClass = transportClass,
                        authority = authority,
                        cooldownUntil = envelope.cooldownUntil,
                    )
                } else {
                    null
                }
            }

            DirectModeOutcome.OWNED_STACK_ONLY -> {
                DirectModeVerdict(
                    result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                    reasonCode = reasonCode,
                    transportClass = transportClass,
                    authority = authority,
                    cooldownUntil = envelope.cooldownUntil,
                )
            }

            DirectModeOutcome.NO_DIRECT_SOLUTION -> {
                DirectModeVerdict(
                    result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                    reasonCode = reasonCode,
                    transportClass = transportClass,
                    authority = authority,
                    cooldownUntil = envelope.cooldownUntil,
                )
            }
        }
    return DirectModePolicyEvaluation(
        authority = authority,
        envelope = envelope,
        verdict = verdict,
    )
}

private fun DirectModePolicyEvaluation.priority(): Int =
    when (verdict?.result) {
        DirectModeVerdictResult.OWNED_STACK_ONLY -> 3
        DirectModeVerdictResult.NO_DIRECT_SOLUTION -> 2
        DirectModeVerdictResult.TRANSPARENT_WORKS -> 1
        null -> 0
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
    return digest.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun String.normalizeDirectModeAuthority(): String = trim().trimEnd('.').lowercase(Locale.US)
