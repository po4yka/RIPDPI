package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapResult
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapSpoofingDetector
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class DnsIntegrityVerdict {
    DNS_OK,
    FAKE_IP,
    DNS_SUBSTITUTION,
    DNS_INTERCEPTION,
    FAKE_NXDOMAIN,
    DOH_BLOCKED,
    UNKNOWN,
    DNS_DISAGREEMENT,
    UDP_UNAVAILABLE,
    ENCRYPTED_UNAVAILABLE,
    DNS_NEGATIVE,
}

data class DnsIntegrityDomainResult(
    val domain: String,
    val verdict: DnsIntegrityVerdict,
    val udpRecords: List<String>,
    val dohJsonIps: Set<String>,
    val dohWireIps: Set<String>,
    val notes: List<String> = emptyList(),
    val udpResponse: DnsResponseSemantics? = null,
    val dohJsonResponse: DnsResponseSemantics? = null,
    val dohWireResponse: DnsResponseSemantics? = null,
) {
    val udpIps: Set<String> = udpRecords.filter(::isDnsIpv4).toSet()
    val dohIps: Set<String> = dohJsonIps + dohWireIps
}

data class DnsIntegrityResult(
    val domains: List<DnsIntegrityDomainResult>,
    val stubIps: Set<String>,
    val dohBlocked: Int,
    val doqResults: List<DoqProbeResult> = emptyList(),
    val dohBootstrapResults: List<DohBootstrapResult> = emptyList(),
) {
    val dohUnavailable: Int =
        domains.count {
            it.verdict == DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE || it.verdict == DnsIntegrityVerdict.DOH_BLOCKED
        }
}

class DnsIntegrityChecker(
    private val udpProbe: DnsUdpProbe = DatagramSocketDnsUdpProbe(),
    private val dohJsonProbe: DnsAddressProbe = DohJsonAddressProbe(),
    private val dohWireProbe: DnsAddressProbe = DohWireAddressProbe(),
    private val doqProbe: DoqIntegrityProbe? = null,
    private val dohBootstrapDetector: DohBootstrapSpoofingDetector? = null,
) {
    suspend fun check(domains: List<String>): DnsIntegrityResult {
        val dohBootstrapResults = runDohBootstrapDetector()
        val excludedDohHostnames = dohBootstrapResults.excludedDohHostnames()
        val results = domains.map { domain -> checkDomain(domain, excludedDohHostnames) }
        val doqResults = runDoqProbe(domains, results)
        val stubIps =
            results
                .flatMap { result -> result.udpIps }
                .groupingBy { ip -> ip }
                .eachCount()
                .filterValues { count -> count >= StubIpMinimumOccurrences }
                .keys
                .toSortedSet()
        return DnsIntegrityResult(
            domains = results,
            stubIps = stubIps,
            dohBlocked = results.count { result -> result.verdict == DnsIntegrityVerdict.DOH_BLOCKED },
            doqResults = doqResults,
            dohBootstrapResults = dohBootstrapResults,
        )
    }

    private suspend fun runDohBootstrapDetector(): List<DohBootstrapResult> =
        dohBootstrapDetector
            ?.checkAll()
            ?.values
            ?.toList()
            .orEmpty()

    private fun List<DohBootstrapResult>.excludedDohHostnames(): Set<String> =
        filter { result -> result.verdict != BootstrapVerdict.OK }
            .mapTo(linkedSetOf()) { result -> result.dohHostname }

    private suspend fun runDoqProbe(
        domains: List<String>,
        results: List<DnsIntegrityDomainResult>,
    ): List<DoqProbeResult> {
        val probe = doqProbe ?: return emptyList()
        val dohResults = results.associate { result -> result.domain to result.dohIps.toList() }
        return probe.run(domains, dohResults)
    }

    private suspend fun checkDomain(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): DnsIntegrityDomainResult =
        coroutineScope {
            val udp = async { dnsAttempt { udpProbe.response(domain) } }
            val dohJson = async { dnsAttempt { dohJsonProbe.response(domain, excludedDohHostnames) } }
            val dohWire = async { dnsAttempt { dohWireProbe.response(domain, excludedDohHostnames) } }
            classify(domain, udp.await(), dohJson.await(), dohWire.await())
        }

    private fun classify(
        domain: String,
        udp: DnsProbeAnswer,
        dohJson: DnsProbeAnswer,
        dohWire: DnsProbeAnswer,
    ): DnsIntegrityDomainResult {
        val udpIps = udp.addresses.filter(::isDnsIpv4).toSet()
        val dohIps = (dohJson.addresses + dohWire.addresses).filter(::isDnsIpv4).toSet()
        val encrypted = listOfNotNull(dohJson.response, dohWire.response)
        val verdict =
            when {
                encrypted.size == 2 &&
                    encrypted.all {
                        it.outcome == udp.response?.outcome && it.rcode == udp.response.rcode &&
                            it.outcome in NegativeOutcomes
                    } -> {
                    DnsIntegrityVerdict.DNS_NEGATIVE
                }

                udpIps.isNotEmpty() && encrypted.any { it.outcome in NegativeOutcomes } -> {
                    DnsIntegrityVerdict.DNS_DISAGREEMENT
                }

                dohIps.isEmpty() -> {
                    DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE
                }

                udpIps.any(::isFakeIp) -> {
                    DnsIntegrityVerdict.FAKE_IP
                }

                udpIps.intersect(dohIps).isNotEmpty() -> {
                    DnsIntegrityVerdict.DNS_OK
                }

                udpIps.isNotEmpty() || udp.response?.outcome in NegativeOutcomes ||
                    DnsWireBuilder.NXDOMAIN in udp.addresses -> {
                    DnsIntegrityVerdict.DNS_DISAGREEMENT
                }

                else -> {
                    DnsIntegrityVerdict.UDP_UNAVAILABLE
                }
            }
        return DnsIntegrityDomainResult(
            domain = domain,
            verdict = verdict,
            udpRecords = udp.legacyRecords(),
            dohJsonIps = dohJson.addresses.filter(::isDnsIpv4).toSet(),
            dohWireIps = dohWire.addresses.filter(::isDnsIpv4).toSet(),
            notes = verdict.notes(),
            udpResponse = udp.response,
            dohJsonResponse = dohJson.response,
            dohWireResponse = dohWire.response,
        )
    }

    private suspend fun DnsUdpProbe.response(domain: String): DnsProbeAnswer =
        if (this is SemanticDnsUdpProbe) resolveResponse(domain) else DnsProbeAnswer(resolveA(domain))

    private suspend fun DnsAddressProbe.response(
        domain: String,
        excluded: Set<String>,
    ): DnsProbeAnswer =
        when (this) {
            is SemanticDnsAddressProbe -> resolveResponse(domain, excluded)
            is BootstrapFilterableDnsAddressProbe -> DnsProbeAnswer(resolveA(domain, excluded).toList())
            else -> DnsProbeAnswer(resolveA(domain).toList())
        }

    private fun DnsIntegrityVerdict.notes(): List<String> =
        when (this) {
            DnsIntegrityVerdict.DNS_OK -> {
                emptyList()
            }

            DnsIntegrityVerdict.FAKE_IP -> {
                listOf("UDP resolver returned a 198.18/15 fake-IP answer")
            }

            DnsIntegrityVerdict.DNS_SUBSTITUTION -> {
                listOf("UDP and DoH answers have no shared A record")
            }

            DnsIntegrityVerdict.DNS_INTERCEPTION -> {
                listOf("UDP/53 failed while DoH returned A records")
            }

            DnsIntegrityVerdict.FAKE_NXDOMAIN -> {
                listOf("UDP returned NXDOMAIN while DoH resolved the domain")
            }

            DnsIntegrityVerdict.DOH_BLOCKED -> {
                listOf("Both DoH comparison methods failed")
            }

            DnsIntegrityVerdict.DNS_DISAGREEMENT -> {
                listOf(
                    "Resolvers returned different DNS answers; the cause is unverified",
                )
            }

            DnsIntegrityVerdict.UDP_UNAVAILABLE -> {
                listOf(
                    "UDP supplied no usable answer; this does not prove interception",
                )
            }

            DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE -> {
                listOf(
                    "No usable encrypted comparison; this does not prove blocking",
                )
            }

            DnsIntegrityVerdict.DNS_NEGATIVE -> {
                listOf("Resolvers returned the same negative DNS outcome")
            }

            DnsIntegrityVerdict.UNKNOWN -> {
                listOf("DNS comparison was inconclusive")
            }
        }

    private companion object {
        private const val StubIpMinimumOccurrences = 2
    }
}

private val NegativeOutcomes =
    setOf(
        DnsResponseOutcome.NODATA,
        DnsResponseOutcome.NXDOMAIN,
        DnsResponseOutcome.SERVFAIL,
        DnsResponseOutcome.REFUSED,
        DnsResponseOutcome.OTHER_RCODE,
    )

private fun isFakeIp(ip: String): Boolean = ip.split('.').let { it[0] == "198" && it[1] in setOf("18", "19") }
