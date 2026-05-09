package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import android.net.ConnectivityManager
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import kotlinx.coroutines.CancellationException

internal object IndirectDnsSignals {
    private val KNOWN_PUBLIC_RESOLVERS =
        setOf(
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "8.8.4.4",
            "9.9.9.9",
            "149.112.112.112",
            "208.67.222.222",
            "208.67.220.220",
            "94.140.14.14",
            "94.140.15.15",
            "77.88.8.8",
            "77.88.8.1",
            "76.76.19.19",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
            "2620:fe::fe",
            "2620:fe::9",
            "2620:119:35::35",
            "2620:119:53::53",
            "2a10:50c0::ad1:ff",
            "2a10:50c0::ad2:ff",
        )

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun checkDns(
        context: Context,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): IndirectSignalOutcome =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork == null) {
                findings.add(Finding("DNS: active network not found"))
                IndirectSignalOutcome()
            } else {
                val linkProperties = cm.getLinkProperties(activeNetwork)
                if (linkProperties == null) {
                    findings.add(Finding("DNS: LinkProperties unavailable"))
                    IndirectSignalOutcome()
                } else {
                    val dnsServers = linkProperties.dnsServers
                    if (dnsServers.isEmpty()) {
                        findings.add(Finding("DNS servers: not detected"))
                        IndirectSignalOutcome()
                    } else {
                        var detected = false
                        var needsReview = false
                        for (dns in dnsServers) {
                            val addr = dns.hostAddress ?: continue
                            when (classifyDnsAddress(addr)) {
                                IndirectSignsChecker.DnsClassification.LOOPBACK -> {
                                    findings.add(
                                        Finding(
                                            description = "DNS points to localhost: $addr (typical for VPN)",
                                            detected = true,
                                            source = EvidenceSource.DNS,
                                            confidence = EvidenceConfidence.HIGH,
                                        ),
                                    )
                                    evidence.add(
                                        EvidenceItem(
                                            source = EvidenceSource.DNS,
                                            detected = true,
                                            confidence = EvidenceConfidence.HIGH,
                                            description = "DNS resolver uses loopback address $addr",
                                        ),
                                    )
                                    detected = true
                                }

                                IndirectSignsChecker.DnsClassification.PRIVATE_LAN -> {
                                    findings.add(Finding("DNS: $addr (local LAN resolver)"))
                                }

                                IndirectSignsChecker.DnsClassification.PRIVATE_TUNNEL -> {
                                    findings.add(
                                        Finding(
                                            description = "DNS in private subnet: $addr (may indicate VPN tunnel)",
                                            detected = true,
                                            source = EvidenceSource.DNS,
                                            confidence = EvidenceConfidence.MEDIUM,
                                        ),
                                    )
                                    evidence.add(
                                        EvidenceItem(
                                            source = EvidenceSource.DNS,
                                            detected = true,
                                            confidence = EvidenceConfidence.MEDIUM,
                                            description = "DNS resolver uses private tunnel address $addr",
                                        ),
                                    )
                                    detected = true
                                }

                                IndirectSignsChecker.DnsClassification.KNOWN_PUBLIC_RESOLVER -> {
                                    findings.add(
                                        Finding(
                                            description = "DNS uses public resolver: $addr",
                                            needsReview = true,
                                            source = EvidenceSource.DNS,
                                            confidence = EvidenceConfidence.LOW,
                                        ),
                                    )
                                    evidence.add(
                                        EvidenceItem(
                                            source = EvidenceSource.DNS,
                                            detected = true,
                                            confidence = EvidenceConfidence.LOW,
                                            description = "DNS resolver uses known public resolver $addr",
                                        ),
                                    )
                                    needsReview = true
                                }

                                IndirectSignsChecker.DnsClassification.LINK_LOCAL -> {
                                    findings.add(Finding("DNS: $addr (link-local)"))
                                }

                                IndirectSignsChecker.DnsClassification.OTHER_PUBLIC -> {
                                    findings.add(Finding("DNS: $addr"))
                                }
                            }
                        }
                        IndirectSignalOutcome(detected = detected, needsReview = needsReview)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings.add(Finding("Error checking DNS: ${e.message}"))
            IndirectSignalOutcome()
        }

    private fun isPrivate172(addr: String): Boolean {
        val parts = addr.split(".")
        if (parts.size < 2) return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }

    fun classifyDnsAddress(addr: String): IndirectSignsChecker.DnsClassification {
        val normalized = addr.lowercase()
        if (normalized == "::1" || normalized.startsWith("127.")) return IndirectSignsChecker.DnsClassification.LOOPBACK
        if (normalized.startsWith("169.254.") || normalized.startsWith("fe80:")) {
            return IndirectSignsChecker.DnsClassification.LINK_LOCAL
        }
        if (
            normalized.startsWith("10.") ||
            (normalized.startsWith("172.") && isPrivate172(normalized)) ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd")
        ) {
            return IndirectSignsChecker.DnsClassification.PRIVATE_TUNNEL
        }
        if (normalized.startsWith("192.168.")) return IndirectSignsChecker.DnsClassification.PRIVATE_LAN
        if (normalized in KNOWN_PUBLIC_RESOLVERS) return IndirectSignsChecker.DnsClassification.KNOWN_PUBLIC_RESOLVER
        return IndirectSignsChecker.DnsClassification.OTHER_PUBLIC
    }
}
