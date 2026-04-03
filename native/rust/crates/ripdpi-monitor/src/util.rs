use std::collections::BTreeSet;
use std::net::SocketAddr;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use super::ScanKind;
use crate::types::{DiagnosticProfileFamily, ScanPathMode};

// --- Constants ---

pub(crate) const DEFAULT_DNS_SERVER: &str = "94.140.14.14:53";
pub(crate) const DEFAULT_DOH_URL: &str = "https://dns.adguard-dns.com/dns-query";
pub(crate) const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["94.140.14.14", "94.140.15.15"];
pub(crate) const DEFAULT_DOH_HOST: &str = "dns.adguard-dns.com";
pub(crate) const DEFAULT_DOH_PORT: u16 = 443;
pub(crate) const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
pub(crate) const IO_TIMEOUT: Duration = Duration::from_millis(1200);
pub(crate) const MAX_HTTP_BYTES: usize = 64 * 1024;
pub(crate) const FAT_HEADER_REQUESTS: usize = 16;
pub(crate) const FAT_HEADER_THRESHOLD_BYTES: usize = 16 * 1024;
pub(crate) const STRATEGY_PROBE_SUITE_QUICK_V1: &str = "quick_v1";
pub(crate) const STRATEGY_PROBE_SUITE_FULL_MATRIX_V1: &str = "full_matrix_v1";
pub(crate) const HTTP_FAKE_PROFILE_CLOUDFLARE_GET: &str = "cloudflare_get";
pub(crate) const TLS_FAKE_PROFILE_GOOGLE_CHROME: &str = "google_chrome";
pub(crate) const UDP_FAKE_PROFILE_DNS_QUERY: &str = "dns_query";
pub(crate) const TELEGRAM_DOWNLOAD_EXPECTED_BYTES: usize = 32_482_836;
pub(crate) const TELEGRAM_CHUNK_SIZE: usize = 16 * 1024;
pub(crate) const TELEGRAM_SPEED_SAMPLE_INTERVAL: Duration = Duration::from_millis(500);

// --- Serde default functions ---

pub(crate) fn default_http_path() -> String {
    "/".to_string()
}

pub(crate) fn default_quic_port() -> u16 {
    443
}

pub(crate) fn default_strategy_probe_suite() -> String {
    STRATEGY_PROBE_SUITE_QUICK_V1.to_string()
}

pub(crate) fn default_scan_kind() -> ScanKind {
    ScanKind::Connectivity
}

pub(crate) fn default_diagnostic_profile_family() -> DiagnosticProfileFamily {
    DiagnosticProfileFamily::General
}

pub(crate) fn default_throughput_window_bytes() -> usize {
    8 * 1024 * 1024
}

pub(crate) fn default_throughput_runs() -> usize {
    2
}

pub(crate) fn default_diagnosis_severity() -> String {
    "warning".to_string()
}

pub(crate) fn default_telegram_dc_port() -> u16 {
    443
}

pub(crate) fn default_telegram_stall_timeout_ms() -> u64 {
    10_000
}

pub(crate) fn default_telegram_total_timeout_ms() -> u64 {
    60_000
}

pub(crate) fn default_telegram_upload_size() -> usize {
    10_485_760
}

// --- Hashing and scheduling ---

pub(crate) fn stable_probe_hash(seed: u64, value: &str) -> u64 {
    let mut hash = seed ^ 0xcbf29ce484222325;
    for byte in value.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

pub(crate) fn ranged_probe_delay(seed: u64, lhs: &str, rhs: &str, min_ms: u64, max_ms: u64) -> u64 {
    if max_ms <= min_ms {
        return min_ms;
    }
    let spread = max_ms - min_ms;
    min_ms + (stable_probe_hash(stable_probe_hash(seed, lhs), rhs) % (spread + 1))
}

pub(crate) fn probe_session_seed(network_scope_key: Option<&str>, session_id: &str) -> u64 {
    stable_probe_hash(stable_probe_hash(0x9e37_79b9_7f4a_7c15, network_scope_key.unwrap_or("default")), session_id)
}

// --- Formatting helpers ---

pub(crate) fn ip_set(values: &[String]) -> BTreeSet<String> {
    values.iter().cloned().collect()
}

pub(crate) fn format_result_set(result: &Result<Vec<String>, String>) -> String {
    match result {
        Ok(values) => values.join("|"),
        Err(err) => format!("error:{err}"),
    }
}

pub(crate) fn format_socket_result(result: &Result<Vec<SocketAddr>, String>) -> String {
    match result {
        Ok(values) => values.iter().map(SocketAddr::to_string).collect::<Vec<_>>().join("|"),
        Err(err) => format!("error:{err}"),
    }
}

// --- Byte parsing ---

pub(crate) fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n")
}

pub(crate) fn parse_content_length(headers: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(headers);
    for line in text.split("\r\n") {
        let (name, value) = line.split_once(':')?;
        if name.trim().eq_ignore_ascii_case("content-length") {
            return value.trim().parse::<usize>().ok();
        }
    }
    None
}

// --- Fat-header thresholds ---

pub(crate) fn fat_threshold_reached(bytes_sent: usize) -> bool {
    bytes_sent >= FAT_HEADER_THRESHOLD_BYTES.saturating_sub(2 * 1024)
}

pub(crate) fn late_stage_cutoff(bytes_sent: usize, responses_seen: usize) -> bool {
    fat_threshold_reached(bytes_sent) || (responses_seen >= 1 && bytes_sent >= 8 * 1024)
}

// --- Probe outcome helpers ---

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ProbeOutcomeBucket {
    Healthy,
    Attention,
    Failed,
    Inconclusive,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct ProbeOutcomeClassification {
    pub(crate) bucket: ProbeOutcomeBucket,
    pub(crate) event_level: &'static str,
    pub(crate) healthy_enough_for_summary: bool,
}

pub(crate) fn classify_probe_outcome(
    probe_type: &str,
    path_mode: &ScanPathMode,
    outcome: &str,
) -> ProbeOutcomeClassification {
    let bucket = probe_outcome_bucket(probe_type, path_mode, outcome);
    ProbeOutcomeClassification {
        bucket,
        event_level: match bucket {
            ProbeOutcomeBucket::Healthy => "info",
            ProbeOutcomeBucket::Failed => "error",
            ProbeOutcomeBucket::Attention | ProbeOutcomeBucket::Inconclusive => "warn",
        },
        healthy_enough_for_summary: matches!(bucket, ProbeOutcomeBucket::Healthy),
    }
}

pub(crate) fn event_level_for_outcome(probe_type: &str, path_mode: &ScanPathMode, outcome: &str) -> &'static str {
    classify_probe_outcome(probe_type, path_mode, outcome).event_level
}

fn probe_outcome_bucket(probe_type: &str, path_mode: &ScanPathMode, outcome: &str) -> ProbeOutcomeBucket {
    match probe_type {
        "network_environment" => match outcome {
            "network_available" => ProbeOutcomeBucket::Healthy,
            "network_unavailable" => ProbeOutcomeBucket::Failed,
            _ => legacy_outcome_bucket(outcome),
        },
        "dns_integrity" => match outcome {
            "dns_match" => ProbeOutcomeBucket::Healthy,
            "dns_expected_mismatch" => ProbeOutcomeBucket::Attention,
            "udp_blocked" if matches!(path_mode, ScanPathMode::RawPath) => ProbeOutcomeBucket::Attention,
            "udp_blocked" => ProbeOutcomeBucket::Inconclusive,
            "udp_skipped_or_blocked" if matches!(path_mode, ScanPathMode::InPath) => ProbeOutcomeBucket::Attention,
            "udp_skipped_or_blocked" => ProbeOutcomeBucket::Inconclusive,
            "dns_substitution" | "dns_nxdomain" | "encrypted_dns_blocked" | "dns_unavailable" => {
                ProbeOutcomeBucket::Failed
            }
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
            "tcp_16kb_blocked" => ProbeOutcomeBucket::Attention,
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
            "unknown"
            | "dns_tampering"
            | "tcp_reset"
            | "silent_drop"
            | "tls_alert"
            | "http_blockpage"
            | "quic_breakage"
            | "redirect"
            | "tls_handshake_failure"
            | "connect_failure"
            | "connection_freeze"
            | "strategy_execution_failure" => ProbeOutcomeBucket::Failed,
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

// --- Timestamp ---

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};

    use super::*;
    use serde::Deserialize;

    #[test]
    fn stable_probe_hash_is_deterministic() {
        let a = stable_probe_hash(42, "hello");
        let b = stable_probe_hash(42, "hello");
        assert_eq!(a, b);
    }

    #[test]
    fn stable_probe_hash_differs_for_different_inputs() {
        let a = stable_probe_hash(42, "hello");
        let b = stable_probe_hash(42, "world");
        assert_ne!(a, b);
    }

    #[test]
    fn stable_probe_hash_differs_for_different_seeds() {
        let a = stable_probe_hash(1, "hello");
        let b = stable_probe_hash(2, "hello");
        assert_ne!(a, b);
    }

    #[test]
    fn ranged_probe_delay_stays_within_bounds() {
        for seed in 0..100 {
            let result = ranged_probe_delay(seed, "a", "b", 100, 200);
            assert!((100..=200).contains(&result), "got {result}");
        }
    }

    #[test]
    fn ranged_probe_delay_returns_min_when_max_equals_min() {
        assert_eq!(ranged_probe_delay(42, "a", "b", 100, 100), 100);
    }

    #[test]
    fn ranged_probe_delay_returns_min_when_max_less_than_min() {
        assert_eq!(ranged_probe_delay(42, "a", "b", 200, 100), 200);
    }

    #[test]
    fn ip_set_deduplicates_values() {
        let input = vec!["1.1.1.1".to_string(), "2.2.2.2".to_string(), "1.1.1.1".to_string()];
        let result = ip_set(&input);
        assert_eq!(result.len(), 2);
        assert!(result.contains("1.1.1.1"));
        assert!(result.contains("2.2.2.2"));
    }

    #[test]
    fn find_headers_end_locates_crlf_boundary() {
        assert_eq!(find_headers_end(b"HTTP/1.1 200 OK\r\n\r\nbody"), Some(15));
    }

    #[test]
    fn find_headers_end_returns_none_when_missing() {
        assert_eq!(find_headers_end(b"no boundary here"), None);
    }

    #[test]
    fn parse_content_length_extracts_value() {
        assert_eq!(parse_content_length(b"Content-Length: 42\r\nOther: val"), Some(42));
    }

    #[test]
    fn parse_content_length_is_case_insensitive() {
        assert_eq!(parse_content_length(b"content-length: 100\r\n"), Some(100));
    }

    #[test]
    fn parse_content_length_returns_none_when_missing() {
        assert_eq!(parse_content_length(b"Other: val\r\n"), None);
    }

    #[test]
    fn fat_threshold_reached_at_boundary() {
        assert!(!fat_threshold_reached(0));
        assert!(fat_threshold_reached(FAT_HEADER_THRESHOLD_BYTES));
        assert!(fat_threshold_reached(FAT_HEADER_THRESHOLD_BYTES - 2 * 1024));
        assert!(!fat_threshold_reached(FAT_HEADER_THRESHOLD_BYTES - 2 * 1024 - 1));
    }

    #[test]
    fn late_stage_cutoff_combines_conditions() {
        assert!(late_stage_cutoff(FAT_HEADER_THRESHOLD_BYTES, 0));
        assert!(late_stage_cutoff(8 * 1024, 1));
        assert!(!late_stage_cutoff(8 * 1024, 0));
        assert!(!late_stage_cutoff(7 * 1024, 1));
    }

    #[test]
    fn classify_probe_outcome_marks_expected_health_buckets() {
        assert_eq!(
            classify_probe_outcome("network_environment", &ScanPathMode::RawPath, "network_available").bucket,
            ProbeOutcomeBucket::Healthy,
        );
        assert_eq!(
            classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "dns_match").bucket,
            ProbeOutcomeBucket::Healthy,
        );
        assert_eq!(
            classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "dns_expected_mismatch").bucket,
            ProbeOutcomeBucket::Attention,
        );
        assert_eq!(
            classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "udp_blocked").bucket,
            ProbeOutcomeBucket::Attention,
        );
        assert_eq!(
            classify_probe_outcome("dns_integrity", &ScanPathMode::InPath, "udp_skipped_or_blocked").bucket,
            ProbeOutcomeBucket::Attention,
        );
        assert_eq!(
            classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "encrypted_dns_blocked").bucket,
            ProbeOutcomeBucket::Failed,
        );
        assert_eq!(
            classify_probe_outcome("tcp_fat_header", &ScanPathMode::RawPath, "whitelist_sni_ok").bucket,
            ProbeOutcomeBucket::Healthy,
        );
        assert_eq!(
            classify_probe_outcome("tcp_fat_header", &ScanPathMode::RawPath, "whitelist_sni_failed").bucket,
            ProbeOutcomeBucket::Failed,
        );
    }

    #[test]
    fn classify_probe_outcome_returns_inconclusive_for_unknown_token() {
        assert_eq!(
            classify_probe_outcome("domain_reachability", &ScanPathMode::RawPath, "tls_experimental").bucket,
            ProbeOutcomeBucket::Inconclusive,
        );
    }

    #[test]
    fn event_level_for_outcome_returns_info_for_success() {
        assert_eq!(event_level_for_outcome("domain_reachability", &ScanPathMode::RawPath, "tls_ok"), "info");
    }

    #[test]
    fn event_level_for_outcome_returns_warn_for_failure() {
        assert_eq!(
            event_level_for_outcome("domain_reachability", &ScanPathMode::RawPath, "tls_handshake_failed"),
            "warn",
        );
    }

    #[test]
    fn fixture_driven_outcome_taxonomy_matches_classifier() {
        let fixture: OutcomeTaxonomyFixture = serde_json::from_str(
            &fs::read_to_string(repo_root().join("diagnostics-contract-fixtures/outcome_taxonomy_current.json"))
                .expect("fixture"),
        )
        .expect("outcome taxonomy");

        assert_eq!(fixture.schema_version, 1);
        for entry in fixture.outcomes {
            let classification = classify_probe_outcome(&entry.probe_type, &entry.path_mode, &entry.outcome);
            assert_eq!(bucket_name(classification.bucket), entry.bucket);
            assert_eq!(ui_tone_name(classification.bucket), entry.ui_tone);
            assert_eq!(classification.event_level, entry.event_level);
            assert_eq!(classification.healthy_enough_for_summary, entry.healthy_enough_for_summary);
        }
    }

    #[test]
    fn format_result_set_joins_ok_values() {
        let result: Result<Vec<String>, String> = Ok(vec!["1.1.1.1".to_string(), "2.2.2.2".to_string()]);
        assert_eq!(format_result_set(&result), "1.1.1.1|2.2.2.2");
    }

    #[test]
    fn format_result_set_prefixes_error() {
        let result: Result<Vec<String>, String> = Err("timeout".to_string());
        assert_eq!(format_result_set(&result), "error:timeout");
    }

    #[test]
    fn probe_session_seed_is_deterministic() {
        let a = probe_session_seed(Some("wifi"), "session-1");
        let b = probe_session_seed(Some("wifi"), "session-1");
        assert_eq!(a, b);
    }

    #[test]
    fn probe_session_seed_uses_default_scope_when_none() {
        let with_none = probe_session_seed(None, "session-1");
        let with_default = probe_session_seed(Some("default"), "session-1");
        assert_eq!(with_none, with_default);
    }

    fn repo_root() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../").canonicalize().expect("repo root")
    }

    fn bucket_name(bucket: ProbeOutcomeBucket) -> &'static str {
        match bucket {
            ProbeOutcomeBucket::Healthy => "Healthy",
            ProbeOutcomeBucket::Attention => "Attention",
            ProbeOutcomeBucket::Failed => "Failed",
            ProbeOutcomeBucket::Inconclusive => "Inconclusive",
        }
    }

    fn ui_tone_name(bucket: ProbeOutcomeBucket) -> &'static str {
        match bucket {
            ProbeOutcomeBucket::Healthy => "Positive",
            ProbeOutcomeBucket::Attention => "Warning",
            ProbeOutcomeBucket::Failed => "Negative",
            ProbeOutcomeBucket::Inconclusive => "Neutral",
        }
    }

    #[derive(Debug, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct OutcomeTaxonomyFixture {
        schema_version: u32,
        outcomes: Vec<OutcomeTaxonomyFixtureEntry>,
    }

    #[derive(Debug, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct OutcomeTaxonomyFixtureEntry {
        probe_type: String,
        path_mode: ScanPathMode,
        outcome: String,
        bucket: String,
        ui_tone: String,
        event_level: String,
        healthy_enough_for_summary: bool,
    }
}
