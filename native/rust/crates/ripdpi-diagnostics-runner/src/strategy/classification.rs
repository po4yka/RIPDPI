use std::net::{IpAddr, SocketAddr};

use crate::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::util::{classify_dns_answer_overlap, DnsAnswerOverlap};

pub(super) struct StrategyDnsClassification {
    pub(super) tampering_detected: bool,
    pub(super) outcome: &'static str,
    pub(super) encrypted_addresses: Vec<String>,
    pub(super) encrypted_ips: Vec<IpAddr>,
}

pub(super) fn classify_target_dns_integrity(
    system_targets: &[SocketAddr],
    system_resolution_failed: bool,
    system_latency_ms: &str,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> Option<StrategyDnsClassification> {
    let encrypted_addresses =
        oracle_assessment.selected.as_ref().map(|selected| selected.value.addresses.clone()).unwrap_or_default();
    let encrypted_ips = encrypted_addresses.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect::<Vec<_>>();

    let (tampering_detected, outcome) = if system_resolution_failed
        && oracle_assessment.trust.allows_tampering_classification()
    {
        (true, "dns_nxdomain_mismatch")
    } else if system_resolution_failed {
        // Both failed or the encrypted oracle was not trusted enough to prove
        // an NXDOMAIN mismatch. Skip to avoid false positives.
        return None;
    } else if !oracle_assessment.trust.allows_tampering_classification() {
        (false, "dns_oracle_unavailable")
    } else if encrypted_ips.is_empty() {
        return None;
    } else {
        let system_ip_strings = system_targets.iter().map(SocketAddr::ip).map(|ip| ip.to_string()).collect::<Vec<_>>();
        let encrypted_ip_strings = encrypted_ips.iter().map(ToString::to_string).collect::<Vec<_>>();
        match classify_dns_answer_overlap(&system_ip_strings, &encrypted_ip_strings) {
            DnsAnswerOverlap::Match => (false, "dns_match"),
            DnsAnswerOverlap::CompatibleDivergence => {
                if system_latency_ms.parse::<u64>().unwrap_or(u64::MAX) <= 5 {
                    (false, "dns_suspicious_divergence")
                } else {
                    (false, "dns_compatible_divergence")
                }
            }
            DnsAnswerOverlap::SinkholeSubstitution => (true, "dns_sinkhole_substitution"),
        }
    };

    Some(StrategyDnsClassification { tampering_detected, outcome, encrypted_addresses, encrypted_ips })
}
