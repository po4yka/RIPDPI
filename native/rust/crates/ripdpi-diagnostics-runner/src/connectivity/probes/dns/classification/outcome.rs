use std::collections::BTreeSet;

use crate::connectivity::adapters::util::{classify_dns_answer_overlap, ip_set, DnsAnswerOverlap};
use crate::types::ScanPathMode;

pub(super) fn classify_dns_probe_outcome(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
    path_mode: &ScanPathMode,
    udp_latency_ms: &str,
    expected: &BTreeSet<String>,
) -> String {
    match (udp_result, encrypted_result) {
        (Ok(udp_ips), Ok(encrypted_ips)) => match classify_dns_answer_overlap(udp_ips, encrypted_ips) {
            DnsAnswerOverlap::Match => {
                if !expected.is_empty() && ip_set(udp_ips) != expected.clone() {
                    "dns_expected_mismatch".to_string()
                } else {
                    "dns_match".to_string()
                }
            }
            DnsAnswerOverlap::CompatibleDivergence => {
                if udp_latency_ms.parse::<u64>().unwrap_or(u64::MAX) <= 5 {
                    "dns_suspicious_divergence".to_string()
                } else {
                    "dns_compatible_divergence".to_string()
                }
            }
            DnsAnswerOverlap::SinkholeSubstitution => "dns_sinkhole_substitution".to_string(),
        },
        (Ok(_), Err(_)) => "dns_oracle_unavailable".to_string(),
        (Err(err), Ok(_)) if err == "dns_nxdomain" => "dns_nxdomain_mismatch".to_string(),
        (Err(_), Ok(_)) => {
            if matches!(path_mode, ScanPathMode::InPath) {
                "udp_skipped_or_blocked".to_string()
            } else {
                "udp_blocked".to_string()
            }
        }
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
    }
}
