use std::collections::BTreeSet;
use std::net::SocketAddr;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use super::ScanKind;
use crate::types::DiagnosticProfileFamily;

// --- Constants ---

pub(crate) const DEFAULT_DNS_SERVER: &str = "1.1.1.1:53";
pub(crate) const DEFAULT_DOH_URL: &str = "https://cloudflare-dns.com/dns-query";
pub(crate) const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["1.1.1.1", "1.0.0.1"];
pub(crate) const DEFAULT_DOH_HOST: &str = "cloudflare-dns.com";
pub(crate) const DEFAULT_DOH_PORT: u16 = 443;
pub(crate) const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
pub(crate) const IO_TIMEOUT: Duration = Duration::from_millis(1200);
pub(crate) const MAX_HTTP_BYTES: usize = 64 * 1024;
pub(crate) const FAT_HEADER_REQUESTS: usize = 16;
pub(crate) const FAT_HEADER_THRESHOLD_BYTES: usize = 16 * 1024;
pub(crate) const MAX_PASSIVE_EVENTS: usize = 256;
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

pub(crate) fn probe_is_success(outcome: &str) -> bool {
    matches!(
        outcome,
        "dns_match"
            | "dns_expected_mismatch"
            | "tls_ok"
            | "tls_version_split"
            | "http_ok"
            | "tcp_fat_header_ok"
            | "whitelist_sni_ok"
            | "quic_initial_response"
            | "quic_response"
            | "service_ok"
            | "circumvention_ok"
            | "throughput_measured"
    )
}

pub(crate) fn event_level_for_outcome(outcome: &str) -> &'static str {
    if probe_is_success(outcome) {
        "info"
    } else {
        "warn"
    }
}

// --- Timestamp ---

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn probe_is_success_recognizes_all_success_outcomes() {
        assert!(probe_is_success("dns_match"));
        assert!(probe_is_success("dns_expected_mismatch"));
        assert!(probe_is_success("tls_ok"));
        assert!(probe_is_success("http_ok"));
        assert!(probe_is_success("tcp_fat_header_ok"));
        assert!(probe_is_success("whitelist_sni_ok"));
    }

    #[test]
    fn probe_is_success_rejects_failure_outcomes() {
        assert!(!probe_is_success("dns_mismatch"));
        assert!(!probe_is_success("tls_error"));
        assert!(!probe_is_success("timeout"));
        assert!(!probe_is_success(""));
    }

    #[test]
    fn event_level_for_outcome_returns_info_for_success() {
        assert_eq!(event_level_for_outcome("tls_ok"), "info");
    }

    #[test]
    fn event_level_for_outcome_returns_warn_for_failure() {
        assert_eq!(event_level_for_outcome("tls_error"), "warn");
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
}
