package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.Finding

internal data class IndirectSignalOutcome(
    val detected: Boolean = false,
    val needsReview: Boolean = false,
)

object IndirectSignsChecker {
    internal enum class DnsClassification {
        LOOPBACK,
        PRIVATE_LAN,
        PRIVATE_TUNNEL,
        KNOWN_PUBLIC_RESOLVER,
        LINK_LOCAL,
        OTHER_PUBLIC,
    }

    fun check(context: Context): CategoryResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        var detected = false
        var needsReview = false

        val notVpnOutcome = IndirectNetworkSignals.checkNotVpnCapability(context, findings, evidence)
        detected = detected || notVpnOutcome.detected

        detected = IndirectNetworkSignals.checkNetworkInterfaces(findings, evidence) || detected
        detected = IndirectNetworkSignals.checkMtu(findings, evidence) || detected
        detected = IndirectNetworkSignals.checkRoutingTable(context, findings, evidence) || detected

        val dnsOutcome = IndirectDnsSignals.checkDns(context, findings, evidence)
        detected = detected || dnsOutcome.detected
        needsReview = needsReview || dnsOutcome.needsReview

        IndirectDumpsysSignals.check(findings)

        val proxySocketScan = ProcNetTcpProxyScanner.scan(context)
        findings.addAll(proxySocketScan.findings)
        evidence.addAll(proxySocketScan.evidence)
        detected = detected || proxySocketScan.outcome.detected
        needsReview = needsReview || proxySocketScan.outcome.needsReview

        return CategoryResult(
            name = "Indirect signs",
            detected = detected,
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }

    internal fun classifyDnsAddress(addr: String): DnsClassification = IndirectDnsSignals.classifyDnsAddress(addr)
}
