package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import java.net.InetAddress
import javax.inject.Inject

internal enum class ResolvedIpFamily {
    IPV4,
    IPV6,
}

internal data class ResolvedMapping(
    val bestIp: String,
    val ipFamily: ResolvedIpFamily,
    val source: DnsMode,
)

internal data class ResolverMappingCandidate(
    val ip: String,
    val source: DnsMode,
    val latencyMs: Long,
    val ttlMs: Long,
    val ipSetDigest: String = "",
)

internal data class ResolverMappingRequest(
    val host: String,
    val networkScopeKey: String,
    val dnsClassification: DirectDnsClassification?,
    val systemCandidates: List<ResolverMappingCandidate>,
    val encryptedCandidates: List<ResolverMappingCandidate>,
    val transportEnvelope: TransportPolicyEnvelope?,
)

internal class ResolverMappingPolicy
    @Inject
    constructor() {
        fun select(request: ResolverMappingRequest): ResolvedMapping? {
            val system = request.systemCandidates.validCandidates(DnsMode.SYSTEM)
            val encrypted =
                request.encryptedCandidates
                    .validCandidates(DnsMode.DOH_PRIMARY, DnsMode.DOH_SECONDARY)
                    .filter { candidate -> candidate.source != DnsMode.SYSTEM }
            val chosen =
                when (request.dnsClassification) {
                    DirectDnsClassification.POISONED -> {
                        encrypted.preferEncrypted()
                    }

                    DirectDnsClassification.DIVERGENT -> {
                        if (request.transportEnvelope.correlatesWithSystemAnswers(system)) {
                            encrypted.preferEncrypted()
                        } else {
                            (system + encrypted).fastest()
                        }
                    }

                    DirectDnsClassification.CLEAN,
                    DirectDnsClassification.ECH_CAPABLE,
                    DirectDnsClassification.NO_HTTPS_RR,
                    null,
                    -> {
                        (system + encrypted).fastest()
                    }
                }
            return chosen?.toResolvedMapping()
        }

        private fun List<ResolverMappingCandidate>.validCandidates(
            vararg allowedSources: DnsMode,
        ): List<ResolverMappingCandidate> {
            val allowed = allowedSources.toSet()
            return filter { candidate ->
                candidate.source in allowed && candidate.ttlMs > 0 && candidate.ip.parseIpFamily() != null
            }
        }

        private fun List<ResolverMappingCandidate>.preferEncrypted(): ResolverMappingCandidate? =
            sortedWith(
                compareBy<ResolverMappingCandidate>(
                    { candidate -> if (candidate.source == DnsMode.DOH_PRIMARY) 0 else 1 },
                    { candidate -> candidate.latencyMs.coerceAtLeast(0L) },
                    ResolverMappingCandidate::ip,
                ),
            ).firstOrNull()

        private fun List<ResolverMappingCandidate>.fastest(): ResolverMappingCandidate? =
            minWithOrNull(
                compareBy<ResolverMappingCandidate>(
                    { candidate -> candidate.latencyMs.coerceAtLeast(0L) },
                    { candidate -> if (candidate.source == DnsMode.SYSTEM) 0 else 1 },
                    ResolverMappingCandidate::ip,
                ),
            )

        private fun TransportPolicyEnvelope?.correlatesWithSystemAnswers(
            systemCandidates: List<ResolverMappingCandidate>,
        ): Boolean =
            this?.let { envelope ->
                val normalizedEnvelopeDigest = envelope.ipSetDigest.normalizeDigest()
                val transportCorrelated =
                    envelope.transportClass == DirectTransportClass.IP_BLOCK_SUSPECT ||
                        envelope.reasonCode == DirectModeReasonCode.IP_BLOCKED
                normalizedEnvelopeDigest.isNotEmpty() &&
                    transportCorrelated &&
                    systemCandidates.any { candidate ->
                        candidate.ipSetDigest.normalizeDigest() == normalizedEnvelopeDigest
                    }
            } ?: false

        private fun ResolverMappingCandidate.toResolvedMapping(): ResolvedMapping? =
            ip.parseIpFamily()?.let { family ->
                ResolvedMapping(bestIp = ip.trim(), ipFamily = family, source = source)
            }

        private fun String.parseIpFamily(): ResolvedIpFamily? =
            runCatching { InetAddress.getByName(trim()) }
                .getOrNull()
                ?.hostAddress
                ?.let { parsed ->
                    if (":" in parsed) {
                        ResolvedIpFamily.IPV6
                    } else {
                        ResolvedIpFamily.IPV4
                    }
                }
    }

private fun String.normalizeDigest(): String = trim().lowercase()
