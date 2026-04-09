package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import android.net.ConnectivityManager
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

object DnsLeakChecker {
    private const val TEST_DOMAIN = "dns-leak-test.ripdpi.local"
    private val KNOWN_SAFE_RESOLVERS =
        setOf(
            "94.140.14.14",
            "94.140.15.15",
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "8.8.4.4",
            "9.9.9.9",
            "149.112.112.112",
        )

    suspend fun check(
        context: Context,
        encryptedDnsEnabled: Boolean = false,
    ): CategoryResult =
        withContext(Dispatchers.IO) {
            val findings = mutableListOf<Finding>()
            val evidence = mutableListOf<EvidenceItem>()
            var detected = false
            var needsReview = false

            val systemDns = getSystemDnsServers(context)
            findings.add(Finding("System DNS servers: ${systemDns.joinToString(", ").ifEmpty { "none" }}"))

            val vpnDns = getVpnDnsServers(context)
            if (vpnDns.isNotEmpty()) {
                findings.add(Finding("VPN DNS servers: ${vpnDns.joinToString(", ")}"))
            }

            val resolvedIp = resolveTestDomain()
            if (resolvedIp != null) {
                findings.add(Finding("DNS resolution test: $resolvedIp"))
            }

            if (systemDns.isNotEmpty() && !encryptedDnsEnabled) {
                val usesIspDns = systemDns.none { it in KNOWN_SAFE_RESOLVERS }
                if (usesIspDns) {
                    needsReview = true
                    findings.add(
                        Finding(
                            description = "DNS uses ISP resolver (not encrypted) - queries visible to ISP",
                            needsReview = true,
                            source = EvidenceSource.DNS,
                            confidence = EvidenceConfidence.MEDIUM,
                        ),
                    )
                    evidence.add(
                        EvidenceItem(
                            source = EvidenceSource.DNS,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "DNS queries sent to ISP resolver without encryption",
                        ),
                    )
                }
            }

            if (encryptedDnsEnabled) {
                findings.add(Finding("Encrypted DNS: enabled"))
            } else {
                findings.add(
                    Finding(
                        description = "Encrypted DNS: disabled - DNS queries are in plaintext",
                        needsReview = true,
                        source = EvidenceSource.DNS,
                        confidence = EvidenceConfidence.LOW,
                    ),
                )
                needsReview = true
            }

            val hasLoopbackDns = systemDns.any { it.startsWith("127.") || it == "::1" }
            if (hasLoopbackDns && !encryptedDnsEnabled) {
                detected = true
                findings.add(
                    Finding(
                        description = "DNS leak risk: loopback DNS without encrypted DNS enabled",
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
                        description = "Loopback DNS resolver without encrypted DNS protection",
                    ),
                )
            }

            CategoryResult(
                name = "DNS Leak",
                detected = detected,
                findings = findings,
                needsReview = needsReview,
                evidence = evidence,
            )
        }

    private fun getSystemDnsServers(context: Context): List<String> =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return emptyList()
            val linkProps = cm.getLinkProperties(network) ?: return emptyList()
            linkProps.dnsServers.mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }

    private fun getVpnDnsServers(context: Context): List<String> =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks
                .mapNotNull { cm.getLinkProperties(it) }
                .flatMap { it.dnsServers }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }

    private fun resolveTestDomain(): String? =
        try {
            val addresses = InetAddress.getAllByName("ifconfig.me")
            addresses.firstOrNull()?.hostAddress
        } catch (_: Exception) {
            null
        }
}
