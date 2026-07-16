package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.TransportPolicyEnvelope
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
            trim().let { value ->
                when {
                    value.isIpv4Literal() -> ResolvedIpFamily.IPV4
                    value.isIpv6Literal() -> ResolvedIpFamily.IPV6
                    else -> null
                }
            }
    }

private fun String.isIpv4Literal(): Boolean {
    val octets = split('.')
    return octets.size == Ipv4OctetCount &&
        octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull()?.let(Ipv4OctetRange::contains) == true
        }
}

private fun String.isIpv6Literal(): Boolean {
    val compressionIndex = indexOf("::")
    val components = split(':').filter(String::isNotEmpty)
    val ipv4Index = components.indexOfFirst { component -> '.' in component }
    val structureValid =
        ':' in this &&
            '%' !in this &&
            compressionIndex == lastIndexOf("::") &&
            (compressionIndex >= 0 || (!startsWith(':') && !endsWith(':')))
    val ipv4Valid =
        ipv4Index < 0 ||
            (ipv4Index == components.lastIndex && components[ipv4Index].isIpv4Literal())
    val hextetsValid =
        components.withIndex().all { (index, component) ->
            index == ipv4Index ||
                (component.length in Ipv6HextetLength && component.all(Char::isHexDigit))
        }
    val unitCount = components.size + if (ipv4Index >= 0) 1 else 0
    val unitCountValid =
        if (compressionIndex >= 0) {
            unitCount < Ipv6HextetCount
        } else {
            unitCount == Ipv6HextetCount
        }
    return structureValid && ipv4Valid && hextetsValid && unitCountValid
}

private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

private fun String.normalizeDigest(): String = trim().lowercase()

private const val Ipv4OctetCount = 4
private val Ipv4OctetRange = 0..255
private const val Ipv6HextetCount = 8
private val Ipv6HextetLength = 1..4
