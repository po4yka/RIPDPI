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

data class DiagnosticsOutcomeClassification(
    val bucket: DiagnosticsOutcomeBucket,
    val uiTone: DiagnosticsOutcomeTone,
    val eventLevel: String,
    val healthyEnoughForSummary: Boolean,
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
            eventLevel = bucket.eventLevel(),
            healthyEnoughForSummary = bucket == DiagnosticsOutcomeBucket.Healthy,
        )
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
                classifyProbeOutcome(
                    probeType = result.probeType,
                    pathMode = pathMode,
                    outcome = result.outcome,
                ).bucket
            }
        return when {
            buckets.any { it == DiagnosticsOutcomeBucket.Failed } -> DiagnosticsOutcomeBucket.Failed
            buckets.any { it == DiagnosticsOutcomeBucket.Attention } -> DiagnosticsOutcomeBucket.Attention
            buckets.any { it == DiagnosticsOutcomeBucket.Inconclusive } -> DiagnosticsOutcomeBucket.Inconclusive
            else -> DiagnosticsOutcomeBucket.Healthy
        }
    }

    private fun bucketForProbeOutcome(
        probeType: String,
        pathMode: ScanPathMode,
        outcome: String,
    ): DiagnosticsOutcomeBucket =
        when (probeType) {
            "network_environment" -> {
                when (outcome) {
                    "network_available" -> DiagnosticsOutcomeBucket.Healthy
                    "network_unavailable" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "dns_integrity" -> {
                when (outcome) {
                    "dns_match" -> {
                        DiagnosticsOutcomeBucket.Healthy
                    }

                    "dns_expected_mismatch" -> {
                        DiagnosticsOutcomeBucket.Attention
                    }

                    "udp_blocked" -> {
                        if (pathMode ==
                            ScanPathMode.RAW_PATH
                        ) {
                            DiagnosticsOutcomeBucket.Attention
                        } else {
                            DiagnosticsOutcomeBucket.Inconclusive
                        }
                    }

                    "udp_skipped_or_blocked" -> {
                        if (pathMode ==
                            ScanPathMode.IN_PATH
                        ) {
                            DiagnosticsOutcomeBucket.Attention
                        } else {
                            DiagnosticsOutcomeBucket.Inconclusive
                        }
                    }

                    "dns_substitution", "encrypted_dns_blocked", "dns_unavailable" -> {
                        DiagnosticsOutcomeBucket.Failed
                    }

                    else -> {
                        legacyBucketForOutcome(outcome)
                    }
                }
            }

            "domain_reachability" -> {
                when (outcome) {
                    "tls_ok" -> DiagnosticsOutcomeBucket.Healthy
                    "tls_version_split", "tls_ech_only", "http_ok" -> DiagnosticsOutcomeBucket.Attention
                    "tls_cert_invalid", "http_blockpage", "unreachable" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "tcp_fat_header" -> {
                when (outcome) {
                    "tcp_fat_header_ok", "tcp_ok", "fat_ok", "whitelist_sni_ok" -> DiagnosticsOutcomeBucket.Healthy

                    "tcp_16kb_blocked" -> DiagnosticsOutcomeBucket.Attention

                    "whitelist_sni_failed",
                    "tcp_reset",
                    "tcp_timeout",
                    "tcp_connect_failed",
                    "tls_handshake_failed",
                    -> DiagnosticsOutcomeBucket.Failed

                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "quic_reachability" -> {
                when (outcome) {
                    "quic_initial_response", "quic_response" -> DiagnosticsOutcomeBucket.Healthy
                    "quic_empty", "quic_error" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "service_reachability" -> {
                when (outcome) {
                    "service_ok" -> DiagnosticsOutcomeBucket.Healthy
                    "service_partial" -> DiagnosticsOutcomeBucket.Attention
                    "service_blocked" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "circumvention_reachability" -> {
                when (outcome) {
                    "circumvention_ok" -> DiagnosticsOutcomeBucket.Healthy
                    "circumvention_blocked" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "telegram_availability" -> {
                when (outcome) {
                    "ok" -> DiagnosticsOutcomeBucket.Healthy
                    "slow", "partial" -> DiagnosticsOutcomeBucket.Attention
                    "blocked", "error" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "throughput_window" -> {
                when (outcome) {
                    "throughput_measured" -> DiagnosticsOutcomeBucket.Healthy
                    "throughput_failed" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "strategy_http" -> {
                when (outcome) {
                    "http_ok" -> DiagnosticsOutcomeBucket.Healthy
                    "http_blockpage", "http_unreachable" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "strategy_https" -> {
                when (outcome) {
                    "tls_ok" -> DiagnosticsOutcomeBucket.Healthy
                    "tls_version_split", "tls_ech_only" -> DiagnosticsOutcomeBucket.Attention
                    "tls_cert_invalid", "tls_handshake_failed" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "strategy_quic" -> {
                when (outcome) {
                    "quic_initial_response", "quic_response" -> DiagnosticsOutcomeBucket.Healthy
                    "quic_empty", "quic_error" -> DiagnosticsOutcomeBucket.Failed
                    else -> legacyBucketForOutcome(outcome)
                }
            }

            "strategy_failure_classification" -> {
                when (outcome) {
                    "unknown",
                    "dns_tampering",
                    "tcp_reset",
                    "silent_drop",
                    "tls_alert",
                    "http_blockpage",
                    "quic_breakage",
                    "redirect",
                    "tls_handshake_failure",
                    "connect_failure",
                    "connection_freeze",
                    "strategy_execution_failure",
                    -> DiagnosticsOutcomeBucket.Failed

                    else -> DiagnosticsOutcomeBucket.Inconclusive
                }
            }

            else -> {
                legacyBucketForOutcome(outcome)
            }
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
}

private fun DiagnosticsOutcomeBucket.eventLevel(): String =
    when (this) {
        DiagnosticsOutcomeBucket.Healthy -> "info"

        DiagnosticsOutcomeBucket.Attention,
        DiagnosticsOutcomeBucket.Inconclusive,
        -> "warn"

        DiagnosticsOutcomeBucket.Failed -> "error"
    }

private fun DiagnosticsOutcomeBucket.uiTone(): DiagnosticsOutcomeTone =
    when (this) {
        DiagnosticsOutcomeBucket.Healthy -> DiagnosticsOutcomeTone.Positive
        DiagnosticsOutcomeBucket.Attention -> DiagnosticsOutcomeTone.Warning
        DiagnosticsOutcomeBucket.Failed -> DiagnosticsOutcomeTone.Negative
        DiagnosticsOutcomeBucket.Inconclusive -> DiagnosticsOutcomeTone.Neutral
    }
