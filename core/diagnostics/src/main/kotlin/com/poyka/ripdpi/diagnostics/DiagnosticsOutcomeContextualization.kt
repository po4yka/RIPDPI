package com.poyka.ripdpi.diagnostics

internal fun ProbeResult.isContextualCdnDnsVariance(reportResults: List<ProbeResult>): Boolean =
    probeType == "dns_integrity" &&
        outcome == "dns_compatible_divergence" &&
        target.isKnownGeoDnsDomain() &&
        hasWeakDnsDivergenceEvidence() &&
        reportResults.hasHealthyReachabilityForDnsTarget(target)

internal fun ProbeResult.isContextualTcpStressProbeSensitivity(reportResults: List<ProbeResult>): Boolean =
    probeType == "tcp_fat_header" &&
        outcome in tcpStressProbeOutcomes &&
        reportResults.hasHealthyDomainReachability() &&
        !reportResults.hasDomainReachabilityFailure()

private fun ProbeResult.hasWeakDnsDivergenceEvidence(): Boolean {
    val detailMap = details.associate { it.key to it.value }
    val hasBooleanStrongSignal = booleanStrongDnsDivergenceKeys.any { detailMap[it] == "true" }
    val hasExpectedHttpsRecord = detailMap["dnsHttpsClass"] == "HTTPS_RR_PRESENT"
    val comparisonScore = detailMap["comparisonScore"]?.toIntOrNull() ?: 0
    val hasStrongComparisonScore = comparisonScore >= StrongDnsComparisonScoreThreshold
    val hasStrongComparisonSignal =
        detailMap["comparisonSignals"]
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .any { it in strongDnsDivergenceSignals }
    return !hasBooleanStrongSignal &&
        hasExpectedHttpsRecord &&
        !hasStrongComparisonScore &&
        !hasStrongComparisonSignal
}

private fun List<ProbeResult>.hasHealthyReachabilityForDnsTarget(dnsTarget: String): Boolean {
    val dnsAuthority = dnsTarget.normalizedProbeAuthority()
    return any { result ->
        result.probeType == "domain_reachability" &&
            result.outcome == "tls_ok" &&
            result.target.normalizedProbeAuthority().isSameDnsSite(dnsAuthority)
    }
}

private fun List<ProbeResult>.hasHealthyDomainReachability(): Boolean =
    any { result -> result.probeType == "domain_reachability" && result.outcome == "tls_ok" }

private fun List<ProbeResult>.hasDomainReachabilityFailure(): Boolean =
    any { result -> result.probeType == "domain_reachability" && result.outcome != "tls_ok" }

private fun String.isKnownGeoDnsDomain(): Boolean {
    val authority = normalizedProbeAuthority()
    return geoDnsDomains.any { authority.isSameDnsSite(it) }
}

private fun String.isSameDnsSite(other: String): Boolean =
    this == other || endsWith(".$other") || other.endsWith(".$this")

internal fun String.normalizedProbeAuthority(): String =
    trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(" (")
        .substringBefore(':')
        .removePrefix("www.")
        .lowercase()

private const val StrongDnsComparisonScoreThreshold = 20

private val booleanStrongDnsDivergenceKeys =
    setOf(
        "recordTypeMismatch",
        "authorityMismatch",
        "malformedPointers",
        "dnsInjectionSuspected",
    )

private val strongDnsDivergenceSignals =
    setOf(
        "record_type_mismatch",
        "rcode_mismatch",
        "extra_cname_in_udp",
        "authority_missing_in_udp",
        "ttl_highly_divergent",
    )

private val geoDnsDomains =
    setOf(
        "google.com",
        "youtube.com",
        "ytimg.com",
        "googlevideo.com",
        "gstatic.com",
        "cloudflare.com",
    )

private val tcpStressProbeOutcomes =
    setOf(
        "tcp_16kb_blocked",
        "tcp_reset",
        "tcp_timeout",
        "tcp_freeze_after_threshold",
        "tls_handshake_failed",
    )
