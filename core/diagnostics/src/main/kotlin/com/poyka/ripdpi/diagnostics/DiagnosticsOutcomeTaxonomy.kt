package com.poyka.ripdpi.diagnostics

enum class DiagnosticsOutcomeBucket {
    Healthy,
    Attention,
    Failed,
    Inconclusive,
}

enum class DiagnosticsOutcomeTone {
    Positive,
    Warning,
    Negative,
    Neutral,
}

enum class DiagnosticsAttentionKind {
    NetworkRisk,
    ProbeArtifact,
}

data class DiagnosticsOutcomeClassification(
    val bucket: DiagnosticsOutcomeBucket,
    val uiTone: DiagnosticsOutcomeTone,
    val eventLevel: String,
    val healthyEnoughForSummary: Boolean,
    val attentionKind: DiagnosticsAttentionKind? = null,
)

object DiagnosticsOutcomeTaxonomy {
    fun classifyProbeOutcome(
        probeType: String,
        pathMode: ScanPathMode,
        outcome: String,
    ): DiagnosticsOutcomeClassification {
        val bucket = bucketForProbeOutcome(probeType = probeType, pathMode = pathMode, outcome = outcome)
        return DiagnosticsOutcomeClassification(
            bucket = bucket,
            uiTone = bucket.uiTone(),
            eventLevel = eventLevelOverride(probeType, outcome) ?: bucket.eventLevel(),
            healthyEnoughForSummary = bucket == DiagnosticsOutcomeBucket.Healthy,
            attentionKind = attentionKindForProbeOutcome(probeType = probeType, outcome = outcome, bucket = bucket),
        )
    }

    fun classifyProbeResult(
        pathMode: ScanPathMode,
        result: ProbeResult,
        reportResults: List<ProbeResult>,
    ): DiagnosticsOutcomeClassification {
        val base =
            classifyProbeOutcome(
                probeType = result.probeType,
                pathMode = pathMode,
                outcome = result.outcome,
            )
        return when {
            result.isContextualCdnDnsVariance(reportResults) -> {
                base.copy(
                    bucket = DiagnosticsOutcomeBucket.Healthy,
                    uiTone = DiagnosticsOutcomeTone.Positive,
                    eventLevel = "info",
                    healthyEnoughForSummary = true,
                    attentionKind = null,
                )
            }

            result.isContextualTcpStressProbeSensitivity(reportResults) -> {
                base.copy(
                    bucket = DiagnosticsOutcomeBucket.Healthy,
                    uiTone = DiagnosticsOutcomeTone.Positive,
                    eventLevel = "info",
                    healthyEnoughForSummary = true,
                    attentionKind = null,
                )
            }

            result.isTlsOkWithHttpFetchArtifact() -> {
                base.copy(attentionKind = DiagnosticsAttentionKind.ProbeArtifact)
            }

            result.isHttpUnreachableWithSuccessfulTls(reportResults) -> {
                base.copy(
                    bucket = DiagnosticsOutcomeBucket.Attention,
                    uiTone = DiagnosticsOutcomeTone.Warning,
                    eventLevel = "warn",
                    healthyEnoughForSummary = false,
                    attentionKind = DiagnosticsAttentionKind.ProbeArtifact,
                )
            }

            else -> {
                base
            }
        }
    }

    fun aggregateBucket(
        pathMode: ScanPathMode,
        results: List<ProbeResult>,
    ): DiagnosticsOutcomeBucket? {
        if (results.isEmpty()) {
            return null
        }
        val buckets =
            results.map { result ->
                classifyProbeResult(
                    pathMode = pathMode,
                    result = result,
                    reportResults = results,
                ).bucket
            }
        return when {
            buckets.any { it == DiagnosticsOutcomeBucket.Failed } -> DiagnosticsOutcomeBucket.Failed
            buckets.any { it == DiagnosticsOutcomeBucket.Attention } -> DiagnosticsOutcomeBucket.Attention
            results.hasOnlyUdpTransientArtifactsOutsideHealthyResults(pathMode) -> DiagnosticsOutcomeBucket.Healthy
            buckets.any { it == DiagnosticsOutcomeBucket.Inconclusive } -> DiagnosticsOutcomeBucket.Inconclusive
            else -> DiagnosticsOutcomeBucket.Healthy
        }
    }
}

private fun bucketForProbeOutcome(
    probeType: String,
    pathMode: ScanPathMode,
    outcome: String,
): DiagnosticsOutcomeBucket =
    when (probeType) {
        "network_environment" -> bucketNetworkEnvironment(outcome)
        "dns_integrity" -> bucketDnsIntegrity(pathMode, outcome)
        "domain_reachability" -> bucketDomainReachability(outcome)
        "tcp_fat_header" -> bucketTcpFatHeader(outcome)
        "quic_reachability" -> bucketQuicReachability(outcome)
        "service_reachability" -> bucketServiceReachability(outcome)
        "circumvention_reachability" -> bucketCircumventionReachability(outcome)
        "telegram_availability" -> bucketTelegramAvailability(outcome)
        "throughput_window" -> bucketThroughputWindow(outcome)
        "strategy_http" -> bucketStrategyHttp(outcome)
        "strategy_https" -> bucketStrategyHttps(outcome)
        "strategy_quic" -> bucketStrategyQuic(outcome)
        "strategy_failure_classification" -> bucketStrategyFailureClassification(outcome)
        "connection_concurrency" -> bucketConnectionConcurrency(outcome)
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketNetworkEnvironment(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "network_available" -> DiagnosticsOutcomeBucket.Healthy
        "network_unavailable" -> DiagnosticsOutcomeBucket.Failed
        "vpn_tunnel_down" -> DiagnosticsOutcomeBucket.Attention
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketDnsIntegrity(
    pathMode: ScanPathMode,
    outcome: String,
): DiagnosticsOutcomeBucket =
    when (outcome) {
        "dns_match" -> {
            DiagnosticsOutcomeBucket.Healthy
        }

        "dns_expected_mismatch",
        "dns_answer_divergence",
        "dns_compatible_divergence",
        "dns_suspicious_divergence",
        -> {
            DiagnosticsOutcomeBucket.Attention
        }

        "udp_blocked" -> {
            if (pathMode == ScanPathMode.RAW_PATH) {
                DiagnosticsOutcomeBucket.Attention
            } else {
                DiagnosticsOutcomeBucket.Inconclusive
            }
        }

        "udp_skipped_or_blocked" -> {
            if (pathMode == ScanPathMode.IN_PATH) {
                DiagnosticsOutcomeBucket.Attention
            } else {
                DiagnosticsOutcomeBucket.Inconclusive
            }
        }

        "dns_substitution",
        "dns_sinkhole_substitution",
        "dns_nxdomain",
        "dns_nxdomain_mismatch",
        "dns_system_resolution_failed",
        "encrypted_dns_blocked",
        "dns_unavailable",
        -> {
            DiagnosticsOutcomeBucket.Failed
        }

        "dns_oracle_unavailable" -> {
            DiagnosticsOutcomeBucket.Inconclusive
        }

        in udpTransientProbeArtifactOutcomes -> {
            DiagnosticsOutcomeBucket.Inconclusive
        }

        else -> {
            legacyBucketForOutcome(outcome)
        }
    }

private fun bucketDomainReachability(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "tls_ok" -> DiagnosticsOutcomeBucket.Healthy
        "tls_version_split", "tls_ech_only", "http_ok" -> DiagnosticsOutcomeBucket.Attention
        "tls_cert_invalid", "http_blockpage", "unreachable" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketTcpFatHeader(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "tcp_fat_header_ok", "tcp_ok", "fat_ok", "whitelist_sni_ok" -> DiagnosticsOutcomeBucket.Healthy

        "tcp_16kb_blocked",
        "tcp_reset",
        "tcp_timeout",
        "tcp_freeze_after_threshold",
        "tls_handshake_failed",
        -> DiagnosticsOutcomeBucket.Attention

        "tcp_connect_failed" -> DiagnosticsOutcomeBucket.Inconclusive

        "whitelist_sni_failed" -> DiagnosticsOutcomeBucket.Failed

        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketQuicReachability(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "quic_initial_response", "quic_response" -> DiagnosticsOutcomeBucket.Healthy
        "quic_empty", "quic_error" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketServiceReachability(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "service_ok" -> DiagnosticsOutcomeBucket.Healthy
        "service_partial" -> DiagnosticsOutcomeBucket.Attention
        "service_blocked" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketCircumventionReachability(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "circumvention_ok" -> DiagnosticsOutcomeBucket.Healthy
        "circumvention_degraded" -> DiagnosticsOutcomeBucket.Attention
        "circumvention_blocked" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketTelegramAvailability(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "ok" -> DiagnosticsOutcomeBucket.Healthy
        "slow", "partial" -> DiagnosticsOutcomeBucket.Attention
        "blocked", "error" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketThroughputWindow(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "throughput_measured" -> DiagnosticsOutcomeBucket.Healthy
        "throughput_failed" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketStrategyHttp(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "http_ok" -> DiagnosticsOutcomeBucket.Healthy
        "http_redirect" -> DiagnosticsOutcomeBucket.Attention
        "http_blockpage", "http_unreachable" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketStrategyHttps(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "tls_ok" -> DiagnosticsOutcomeBucket.Healthy
        "tls_version_split", "tls_ech_only" -> DiagnosticsOutcomeBucket.Attention
        "tls_cert_invalid", "tls_handshake_failed" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketStrategyQuic(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "quic_initial_response", "quic_response" -> DiagnosticsOutcomeBucket.Healthy
        "quic_empty", "quic_error" -> DiagnosticsOutcomeBucket.Failed
        else -> legacyBucketForOutcome(outcome)
    }

private fun bucketStrategyFailureClassification(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "confirm_good_dpi_suspected" -> DiagnosticsOutcomeBucket.Attention

        "unknown",
        "dns_tampering",
        "dns_resolution_failure",
        "tcp_reset",
        "silent_drop",
        "tls_alert",
        "http_blockpage",
        "quic_breakage",
        "redirect",
        "tls_handshake_failure",
        "connect_failure",
        "connection_freeze",
        "ip_block_suspect",
        "shadowtls_version_mismatch",
        "strategy_execution_failure",
        "tuic_version_unsupported",
        -> DiagnosticsOutcomeBucket.Failed

        // Capability-skipped is not a network failure; a required platform
        // feature was absent at runtime (e.g. TTL write unavailable). Surface
        // as Inconclusive so the UI does not show a false negative outcome.
        "capability_skipped" -> DiagnosticsOutcomeBucket.Inconclusive

        else -> DiagnosticsOutcomeBucket.Inconclusive
    }

private fun bucketConnectionConcurrency(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "healthy" -> DiagnosticsOutcomeBucket.Healthy
        "blocked" -> DiagnosticsOutcomeBucket.Attention
        "mixed", "contaminated", "skipped" -> DiagnosticsOutcomeBucket.Inconclusive
        else -> DiagnosticsOutcomeBucket.Inconclusive
    }

private fun legacyBucketForOutcome(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "ok", "success", "completed", "reachable", "allowed" -> DiagnosticsOutcomeBucket.Healthy

        "partial", "mixed", "timeout", "slow", "stalled" -> DiagnosticsOutcomeBucket.Attention

        "failed",
        "blocked",
        "error",
        "reset",
        "unreachable",
        "dns_blocked",
        "substituted",
        -> DiagnosticsOutcomeBucket.Failed

        "skipped", "not_applicable" -> DiagnosticsOutcomeBucket.Inconclusive

        else -> DiagnosticsOutcomeBucket.Inconclusive
    }

private fun DiagnosticsOutcomeBucket.eventLevel(): String =
    when (this) {
        DiagnosticsOutcomeBucket.Healthy -> "info"

        DiagnosticsOutcomeBucket.Attention,
        DiagnosticsOutcomeBucket.Inconclusive,
        -> "warn"

        DiagnosticsOutcomeBucket.Failed -> "error"
    }

/**
 * Some "Failed" outcomes are expected censorship / policy findings rather
 * than infrastructure faults. Logging them at ERROR makes logcat look like the
 * app is crashing; they belong at WARN. Mirrors
 * `ripdpi-diagnostics-contracts/src/util/outcome_policy.rs::event_level_override`.
 */
private fun eventLevelOverride(
    probeType: String,
    outcome: String,
): String? =
    when (probeType to outcome) {
        "dns_integrity" to "dns_substitution",
        "dns_integrity" to "dns_sinkhole_substitution",
        "dns_integrity" to "encrypted_dns_blocked",
        "dns_integrity" to "dns_nxdomain",
        "dns_integrity" to "dns_nxdomain_mismatch",
        "dns_integrity" to "dns_system_resolution_failed",
        "domain_reachability" to "tls_cert_invalid",
        "domain_reachability" to "http_blockpage",
        "service_reachability" to "service_blocked",
        "circumvention_reachability" to "circumvention_blocked",
        "telegram_availability" to "blocked",
        "strategy_http" to "http_blockpage",
        "strategy_https" to "tls_cert_invalid",
        "strategy_failure_classification" to "http_blockpage",
        "strategy_failure_classification" to "dns_tampering",
        "strategy_failure_classification" to "dns_resolution_failure",
        -> "warn"

        else -> null
    }

private fun attentionKindForProbeOutcome(
    probeType: String,
    outcome: String,
    bucket: DiagnosticsOutcomeBucket,
): DiagnosticsAttentionKind? =
    when {
        bucket != DiagnosticsOutcomeBucket.Attention -> {
            if (probeType == "dns_integrity" && outcome in udpTransientProbeArtifactOutcomes) {
                DiagnosticsAttentionKind.ProbeArtifact
            } else {
                null
            }
        }

        probeType == "tcp_fat_header" && outcome in fatHeaderProbeArtifactOutcomes -> {
            DiagnosticsAttentionKind.ProbeArtifact
        }

        else -> {
            DiagnosticsAttentionKind.NetworkRisk
        }
    }

private val fatHeaderProbeArtifactOutcomes =
    setOf(
        "tcp_16kb_blocked",
        "tcp_reset",
        "tcp_timeout",
        "tcp_freeze_after_threshold",
        "tls_handshake_failed",
    )

private val udpTransientProbeArtifactOutcomes =
    setOf(
        "udp_timeout_transient",
        "udp_plain_dns_unstable",
    )

private fun List<ProbeResult>.hasOnlyUdpTransientArtifactsOutsideHealthyResults(pathMode: ScanPathMode): Boolean {
    fun ProbeResult.bucket(): DiagnosticsOutcomeBucket =
        DiagnosticsOutcomeTaxonomy
            .classifyProbeOutcome(
                probeType = probeType,
                pathMode = pathMode,
                outcome = outcome,
            ).bucket

    return any { result -> result.bucket() == DiagnosticsOutcomeBucket.Healthy } &&
        filterNot { result -> result.bucket() == DiagnosticsOutcomeBucket.Healthy }
            .all { result ->
                result.probeType == "dns_integrity" && result.outcome in udpTransientProbeArtifactOutcomes
            }
}

private fun ProbeResult.isTlsOkWithHttpFetchArtifact(): Boolean {
    if (probeType != "domain_reachability" || outcome != "tls_ok") {
        return false
    }
    val details = details.associate { it.key to it.value }
    return details["httpStatus"] == "http_unreachable" ||
        details["httpOutcome"] == "http_unreachable" ||
        details["httpError"] == "response_too_large"
}

private fun ProbeResult.isHttpUnreachableWithSuccessfulTls(reportResults: List<ProbeResult>): Boolean {
    if (outcome != "http_unreachable" || probeType !in httpProbeTypes) {
        return false
    }
    val authority = target.normalizedProbeAuthority()
    return reportResults.any { candidate ->
        candidate.outcome == "tls_ok" &&
            candidate.probeType in tlsProbeTypes &&
            candidate.target.normalizedProbeAuthority() == authority
    }
}

private val httpProbeTypes = setOf("domain_reachability", "strategy_http")
private val tlsProbeTypes = setOf("domain_reachability", "strategy_https")

private fun DiagnosticsOutcomeBucket.uiTone(): DiagnosticsOutcomeTone =
    when (this) {
        DiagnosticsOutcomeBucket.Healthy -> DiagnosticsOutcomeTone.Positive
        DiagnosticsOutcomeBucket.Attention -> DiagnosticsOutcomeTone.Warning
        DiagnosticsOutcomeBucket.Failed -> DiagnosticsOutcomeTone.Negative
        DiagnosticsOutcomeBucket.Inconclusive -> DiagnosticsOutcomeTone.Neutral
    }
