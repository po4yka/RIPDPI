package com.poyka.ripdpi.diagnostics

private const val ProbeTypeTcpFatHeader = "tcp_fat_header"
private const val ProbeTypeDomainReachability = "domain_reachability"
private const val ProbeTypeServiceReachability = "service_reachability"
private const val ProbeTypeCircumventionReachability = "circumvention_reachability"
private const val ProbeTypeThroughputWindow = "throughput_window"
private const val ProbeTypeQuicReachability = "quic_reachability"

private const val OutcomeTlsOk = "tls_ok"
private const val OutcomeTcpReset = "tcp_reset"
private const val OutcomeTcpTimeout = "tcp_timeout"
private const val OutcomeTcp16kbBlocked = "tcp_16kb_blocked"
private const val OutcomeTcpFreezeAfterThreshold = "tcp_freeze_after_threshold"
private const val OutcomeTlsHandshakeFailed = "tls_handshake_failed"
private const val OutcomeUnreachable = "unreachable"
private const val OutcomeTlsCertInvalid = "tls_cert_invalid"
private const val OutcomeHttpBlockpage = "http_blockpage"
private const val OutcomeServiceBlocked = "service_blocked"
private const val OutcomeCircumventionBlocked = "circumvention_blocked"
private const val OutcomeThroughputFailed = "throughput_failed"
private const val OutcomeQuicError = "quic_error"

private const val MinimumHealthyDirectTlsProbesForSyntheticBlocking = 3

internal enum class DirectPathHealthState {
    DIRECT_PATH_HEALTHY,
    DIRECT_PATH_HEALTHY_WITH_SYNTHETIC_ATTENTION,
    DIRECT_PATH_RISK_DETECTED,
    INCONCLUSIVE,
}

private val fatHeaderBlockingOutcomes =
    setOf(
        OutcomeTcpReset,
        OutcomeTcpTimeout,
        OutcomeTcp16kbBlocked,
        OutcomeTcpFreezeAfterThreshold,
        OutcomeTlsHandshakeFailed,
    )

private val realReachabilityBlockingOutcomes =
    setOf(
        ProbeTypeDomainReachability to OutcomeUnreachable,
        ProbeTypeDomainReachability to OutcomeTlsHandshakeFailed,
        ProbeTypeDomainReachability to OutcomeTlsCertInvalid,
        ProbeTypeDomainReachability to OutcomeHttpBlockpage,
        ProbeTypeServiceReachability to OutcomeServiceBlocked,
        ProbeTypeCircumventionReachability to OutcomeCircumventionBlocked,
        ProbeTypeThroughputWindow to OutcomeThroughputFailed,
        ProbeTypeQuicReachability to OutcomeQuicError,
    )

internal fun ScanReport.hasFatHeaderOnlySyntheticBlocking(): Boolean {
    val hasBlockingFatHeader = results.any(ProbeResult::isBlockingFatHeaderProbe)
    val hasRealReachabilityFailure = results.any(ProbeResult::isRealReachabilityBlockingProbe)
    val healthyDirectTlsCount = results.count(ProbeResult::isHealthyDomainTlsReachability)
    return pathMode == ScanPathMode.RAW_PATH &&
        hasBlockingFatHeader &&
        !hasRealReachabilityFailure &&
        healthyDirectTlsCount >= MinimumHealthyDirectTlsProbesForSyntheticBlocking
}

internal fun ScanReport.hasOnlySyntheticFatHeaderBlocking(): Boolean {
    val hasBlockingFatHeader = results.any(ProbeResult::isBlockingFatHeaderProbe)
    val hasRealReachabilityFailure = results.any(ProbeResult::isRealReachabilityBlockingProbe)
    return pathMode == ScanPathMode.RAW_PATH && hasBlockingFatHeader && !hasRealReachabilityFailure
}

internal fun ScanReport.directPathHealthState(): DirectPathHealthState {
    val hasHealthyDirectTls =
        results.count(ProbeResult::isHealthyDomainTlsReachability) >= MinimumHealthyDirectTlsProbesForSyntheticBlocking
    val hasRealReachabilityFailure = results.any(ProbeResult::isRealReachabilityBlockingProbe)
    return when {
        hasFatHeaderOnlySyntheticBlocking() -> {
            DirectPathHealthState.DIRECT_PATH_HEALTHY_WITH_SYNTHETIC_ATTENTION
        }

        pathMode == ScanPathMode.RAW_PATH && hasHealthyDirectTls && !hasRealReachabilityFailure -> {
            DirectPathHealthState.DIRECT_PATH_HEALTHY
        }

        hasRealReachabilityFailure || directModeVerdict != null -> {
            DirectPathHealthState.DIRECT_PATH_RISK_DETECTED
        }

        else -> {
            DirectPathHealthState.INCONCLUSIVE
        }
    }
}

internal fun ScanReport.resultsForDirectModePolicy(): List<ProbeResult> =
    results
        .filter(ProbeResult::isDirectModePolicyEvidence)
        .let { policyResults ->
            if (hasFatHeaderOnlySyntheticBlocking()) {
                policyResults.filterNot(ProbeResult::isTcpFatHeaderProbe)
            } else {
                policyResults
            }
        }

internal fun ProbeResult.isDirectModePolicyEvidence(): Boolean =
    !probeType.startsWith("strategy_") || detailValue("candidateId")?.trim() == "baseline_plain_direct"

private fun ProbeResult.isTcpFatHeaderProbe(): Boolean = probeType == ProbeTypeTcpFatHeader

private fun ProbeResult.isBlockingFatHeaderProbe(): Boolean =
    isTcpFatHeaderProbe() && outcome in fatHeaderBlockingOutcomes

private fun ProbeResult.isRealReachabilityBlockingProbe(): Boolean =
    probeType to outcome in realReachabilityBlockingOutcomes

private fun ProbeResult.isHealthyDomainTlsReachability(): Boolean =
    probeType == ProbeTypeDomainReachability && outcome == OutcomeTlsOk
