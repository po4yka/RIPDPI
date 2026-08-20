use std::fs;
use std::time::{Duration, Instant};

use golden_test_support::repo_root;
use serde::Deserialize;

use crate::types::ScanPathMode;

use super::*;

#[test]
fn scan_io_timeout_is_clamped_to_the_remaining_deadline() {
    let timeout = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(20)), || {
        bounded_scan_io_timeout(Duration::from_secs(1)).expect("remaining timeout")
    });

    assert!(timeout <= Duration::from_millis(20));
    assert!(!timeout.is_zero());
}

#[test]
fn expired_scan_deadline_rejects_new_io() {
    let result = with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
        bounded_scan_io_timeout(Duration::from_secs(1))
    });

    assert_eq!(result, Err("scan_deadline_exceeded"));
}

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
        classify_probe_outcome("network_environment", &ScanPathMode::RawPath, "vpn_tunnel_down").bucket,
        ProbeOutcomeBucket::Attention,
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
        classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "udp_timeout_transient").bucket,
        ProbeOutcomeBucket::Inconclusive,
    );
    assert_eq!(
        classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "udp_plain_dns_unstable").bucket,
        ProbeOutcomeBucket::Inconclusive,
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
        classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "dns_oracle_unavailable").bucket,
        ProbeOutcomeBucket::Inconclusive,
    );
    assert_eq!(
        classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "udp_timeout_transient").bucket,
        ProbeOutcomeBucket::Inconclusive,
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
fn dns_divergence_classification_is_attention() {
    let classification = classify_probe_outcome("dns_integrity", &ScanPathMode::RawPath, "dns_compatible_divergence");
    assert_eq!(classification.bucket, ProbeOutcomeBucket::Attention);
    assert_eq!(classification.event_level, "warn");
}

#[test]
fn event_level_is_warn_for_censorship_findings() {
    for (probe, outcome, bucket) in [
        ("dns_integrity", "dns_sinkhole_substitution", ProbeOutcomeBucket::Failed),
        ("dns_integrity", "dns_nxdomain_mismatch", ProbeOutcomeBucket::Failed),
        ("dns_integrity", "dns_system_resolution_failed", ProbeOutcomeBucket::Failed),
        ("dns_integrity", "dns_suspicious_divergence", ProbeOutcomeBucket::Attention),
        ("domain_reachability", "tls_cert_invalid", ProbeOutcomeBucket::Failed),
        ("domain_reachability", "http_blockpage", ProbeOutcomeBucket::Failed),
        ("service_reachability", "service_blocked", ProbeOutcomeBucket::Failed),
        ("circumvention_reachability", "circumvention_blocked", ProbeOutcomeBucket::Failed),
        ("telegram_availability", "blocked", ProbeOutcomeBucket::Failed),
        ("strategy_failure_classification", "dns_resolution_failure", ProbeOutcomeBucket::Failed),
    ] {
        let c = classify_probe_outcome(probe, &ScanPathMode::RawPath, outcome);
        assert_eq!(c.bucket, bucket, "{probe}/{outcome} should keep its expected bucket");
        assert_eq!(c.event_level, "warn", "{probe}/{outcome} should log at warn");
    }
}

#[test]
fn event_level_stays_error_for_infra_faults() {
    for (probe, outcome) in [
        ("network_environment", "network_unavailable"),
        ("dns_integrity", "dns_unavailable"),
        ("domain_reachability", "unreachable"),
        ("quic_reachability", "quic_error"),
        ("throughput_window", "throughput_failed"),
    ] {
        assert_eq!(
            classify_probe_outcome(probe, &ScanPathMode::RawPath, outcome).event_level,
            "error",
            "{probe}/{outcome} should log at error",
        );
    }
}

#[test]
fn answer_overlap_matches_on_shared_ip() {
    let overlap = classify_dns_answer_overlap(
        &["1.2.3.4".to_string(), "5.6.7.8".to_string()],
        &["5.6.7.8".to_string(), "9.9.9.9".to_string()],
    );
    assert_eq!(overlap, DnsAnswerOverlap::Match);
}

#[test]
fn answer_overlap_matches_on_shared_slash24() {
    let overlap = classify_dns_answer_overlap(&["104.16.132.229".to_string()], &["104.16.132.12".to_string()]);
    assert_eq!(overlap, DnsAnswerOverlap::Match);
}

#[test]
fn answer_overlap_diverges_for_disjoint_public_anycast() {
    let overlap = classify_dns_answer_overlap(&["142.250.75.78".to_string()], &["172.217.20.206".to_string()]);
    assert_eq!(overlap, DnsAnswerOverlap::CompatibleDivergence);
}

#[test]
fn answer_overlap_flags_substitution_on_private_ip() {
    let overlap = classify_dns_answer_overlap(&["10.1.1.1".to_string()], &["142.250.75.78".to_string()]);
    assert_eq!(overlap, DnsAnswerOverlap::SinkholeSubstitution);
}

#[test]
fn answer_overlap_flags_substitution_on_loopback() {
    let overlap = classify_dns_answer_overlap(&["127.0.0.1".to_string()], &["104.16.132.229".to_string()]);
    assert_eq!(overlap, DnsAnswerOverlap::SinkholeSubstitution);
}

#[test]
fn answer_overlap_flags_substitution_on_ipv6_private_and_link_local() {
    let unique_local = classify_dns_answer_overlap(&["fd00::53".to_string()], &["2606:4700:4700::1111".to_string()]);
    assert_eq!(unique_local, DnsAnswerOverlap::SinkholeSubstitution);
    let link_local = classify_dns_answer_overlap(&["fe80::53".to_string()], &["2606:4700:4700::1111".to_string()]);
    assert_eq!(link_local, DnsAnswerOverlap::SinkholeSubstitution);
}

#[test]
fn answer_overlap_matches_on_shared_v6_slash48() {
    let overlap =
        classify_dns_answer_overlap(&["2606:4700:4700::1111".to_string()], &["2606:4700:4700::1001".to_string()]);
    assert_eq!(overlap, DnsAnswerOverlap::Match);
}

#[test]
fn sinkhole_detects_unspecified_and_private() {
    assert!(looks_like_sinkhole("0.0.0.0"));
    assert!(looks_like_sinkhole("127.0.0.1"));
    assert!(looks_like_sinkhole("10.1.2.3"));
    assert!(looks_like_sinkhole("192.168.0.1"));
    assert!(looks_like_sinkhole("::1"));
    assert!(looks_like_sinkhole("fc12:3456::1"));
    assert!(looks_like_sinkhole("fd12:3456::1"));
    assert!(looks_like_sinkhole("fe80::1"));
    assert!(looks_like_sinkhole("febf:ffff::1"));
    assert!(!looks_like_sinkhole("142.250.75.78"));
    assert!(!looks_like_sinkhole("2606:4700:4700::1111"));
    assert!(looks_like_sinkhole("not-an-ip"));
}

#[test]
fn event_level_for_outcome_returns_info_for_success() {
    assert_eq!(event_level_for_outcome("domain_reachability", &ScanPathMode::RawPath, "tls_ok"), "info");
}

#[test]
fn event_level_for_outcome_returns_warn_for_failure() {
    assert_eq!(event_level_for_outcome("domain_reachability", &ScanPathMode::RawPath, "tls_handshake_failed"), "warn",);
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
