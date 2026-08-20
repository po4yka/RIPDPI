use crate::types::ScanPathMode;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeOutcomeBucket {
    Healthy,
    Attention,
    Failed,
    Inconclusive,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProbeOutcomeClassification {
    pub bucket: ProbeOutcomeBucket,
    pub event_level: &'static str,
    pub healthy_enough_for_summary: bool,
}

pub fn classify_probe_outcome(probe_type: &str, path_mode: &ScanPathMode, outcome: &str) -> ProbeOutcomeClassification {
    let bucket = probe_outcome_bucket(probe_type, path_mode, outcome);
    let default_level = match bucket {
        ProbeOutcomeBucket::Healthy => "info",
        ProbeOutcomeBucket::Failed => "error",
        ProbeOutcomeBucket::Attention | ProbeOutcomeBucket::Inconclusive => "warn",
    };
    ProbeOutcomeClassification {
        bucket,
        event_level: event_level_override(probe_type, outcome).unwrap_or(default_level),
        healthy_enough_for_summary: matches!(bucket, ProbeOutcomeBucket::Healthy),
    }
}

pub fn event_level_for_outcome(probe_type: &str, path_mode: &ScanPathMode, outcome: &str) -> &'static str {
    classify_probe_outcome(probe_type, path_mode, outcome).event_level
}

/// Some "failed" outcomes represent expected censorship/policy **findings**
/// rather than infrastructure faults. Logging them at ERROR makes logcat look
/// like the app is crashing; they belong at WARN. Returns `Some("warn")` for
/// those cases, `None` otherwise.
fn event_level_override(probe_type: &str, outcome: &str) -> Option<&'static str> {
    match (probe_type, outcome) {
        (
            "dns_integrity",
            "dns_sinkhole_substitution"
            | "dns_nxdomain_mismatch"
            | "dns_system_resolution_failed"
            | "dns_suspicious_divergence",
        )
        | ("domain_reachability", "tls_cert_invalid" | "http_blockpage")
        | ("service_reachability", "service_blocked")
        | ("circumvention_reachability", "circumvention_blocked")
        | ("telegram_availability", "blocked")
        | ("strategy_http", "http_blockpage")
        | ("strategy_https", "tls_cert_invalid")
        | ("strategy_failure_classification", "http_blockpage" | "dns_tampering" | "dns_resolution_failure") => {
            Some("warn")
        }
        _ => None,
    }
}

fn probe_outcome_bucket(probe_type: &str, path_mode: &ScanPathMode, outcome: &str) -> ProbeOutcomeBucket {
    match probe_type {
        "network_environment" => match outcome {
            "network_available" => ProbeOutcomeBucket::Healthy,
            "network_unavailable" => ProbeOutcomeBucket::Failed,
            "vpn_tunnel_down" => ProbeOutcomeBucket::Attention,
            _ => legacy_outcome_bucket(outcome),
        },
        "dns_integrity" => match outcome {
            "dns_match" => ProbeOutcomeBucket::Healthy,
            "dns_expected_mismatch" | "dns_compatible_divergence" | "dns_suspicious_divergence" => {
                ProbeOutcomeBucket::Attention
            }
            "udp_timeout_transient" | "udp_plain_dns_unstable" => ProbeOutcomeBucket::Inconclusive,
            "udp_blocked" if matches!(path_mode, ScanPathMode::RawPath) => ProbeOutcomeBucket::Attention,
            "udp_blocked" => ProbeOutcomeBucket::Inconclusive,
            "udp_skipped_or_blocked" if matches!(path_mode, ScanPathMode::InPath) => ProbeOutcomeBucket::Attention,
            "udp_skipped_or_blocked" => ProbeOutcomeBucket::Inconclusive,
            "dns_sinkhole_substitution"
            | "dns_nxdomain_mismatch"
            | "dns_system_resolution_failed"
            | "dns_unavailable" => ProbeOutcomeBucket::Failed,
            "dns_oracle_unavailable" => ProbeOutcomeBucket::Inconclusive,
            _ => legacy_outcome_bucket(outcome),
        },
        "domain_reachability" => match outcome {
            "tls_ok" => ProbeOutcomeBucket::Healthy,
            "tls_version_split" | "tls_ech_only" | "http_ok" => ProbeOutcomeBucket::Attention,
            "tls_cert_invalid" | "http_blockpage" | "unreachable" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "tcp_fat_header" => match outcome {
            "tcp_fat_header_ok" | "tcp_ok" | "fat_ok" | "whitelist_sni_ok" => ProbeOutcomeBucket::Healthy,
            "tcp_16kb_blocked" | "tcp_freeze_after_threshold" => ProbeOutcomeBucket::Attention,
            "tcp_connect_failed" => ProbeOutcomeBucket::Inconclusive,
            "tcp_reset" | "tcp_timeout" | "tls_handshake_failed" => ProbeOutcomeBucket::Attention,
            "whitelist_sni_failed" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "quic_reachability" => match outcome {
            "quic_initial_response" | "quic_response" => ProbeOutcomeBucket::Healthy,
            "quic_empty" | "quic_error" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "service_reachability" => match outcome {
            "service_ok" => ProbeOutcomeBucket::Healthy,
            "service_partial" => ProbeOutcomeBucket::Attention,
            "service_blocked" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "circumvention_reachability" => match outcome {
            "circumvention_ok" => ProbeOutcomeBucket::Healthy,
            "circumvention_degraded" => ProbeOutcomeBucket::Attention,
            "circumvention_blocked" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "telegram_availability" => match outcome {
            "ok" => ProbeOutcomeBucket::Healthy,
            "slow" | "partial" => ProbeOutcomeBucket::Attention,
            "blocked" | "error" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "throughput_window" => match outcome {
            "throughput_measured" => ProbeOutcomeBucket::Healthy,
            "throughput_failed" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "strategy_http" => match outcome {
            "http_ok" => ProbeOutcomeBucket::Healthy,
            "http_redirect" => ProbeOutcomeBucket::Attention,
            "http_blockpage" | "http_unreachable" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "strategy_https" => match outcome {
            "tls_ok" => ProbeOutcomeBucket::Healthy,
            "tls_version_split" | "tls_ech_only" => ProbeOutcomeBucket::Attention,
            "tls_cert_invalid" | "tls_handshake_failed" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "strategy_quic" => match outcome {
            "quic_initial_response" | "quic_response" => ProbeOutcomeBucket::Healthy,
            "quic_empty" | "quic_error" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "strategy_failure_classification" => match outcome {
            "confirm_good_dpi_suspected" => ProbeOutcomeBucket::Attention,
            "unknown"
            | "dns_tampering"
            | "dns_resolution_failure"
            | "tcp_reset"
            | "silent_drop"
            | "tls_alert"
            | "http_blockpage"
            | "quic_breakage"
            | "redirect"
            | "tls_handshake_failure"
            | "connect_failure"
            | "connection_freeze"
            | "ip_block_suspect"
            | "shadowtls_version_mismatch"
            | "strategy_execution_failure"
            | "tuic_version_unsupported" => ProbeOutcomeBucket::Failed,
            _ => ProbeOutcomeBucket::Inconclusive,
        },
        _ => legacy_outcome_bucket(outcome),
    }
}

fn legacy_outcome_bucket(outcome: &str) -> ProbeOutcomeBucket {
    match outcome {
        "ok" | "success" | "completed" | "reachable" | "allowed" => ProbeOutcomeBucket::Healthy,
        "partial" | "mixed" | "timeout" | "slow" | "stalled" => ProbeOutcomeBucket::Attention,
        "failed" | "blocked" | "error" | "reset" | "unreachable" | "dns_blocked" | "substituted" => {
            ProbeOutcomeBucket::Failed
        }
        "skipped" | "not_applicable" => ProbeOutcomeBucket::Inconclusive,
        _ => ProbeOutcomeBucket::Inconclusive,
    }
}
