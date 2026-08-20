use std::net::{IpAddr, SocketAddr};

use crate::strategy::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::strategy::adapters::transport::DnsResolveError;
use crate::strategy::adapters::util::{DnsAnswerOverlap, classify_dns_answer_overlap};

pub(super) struct StrategyDnsClassification {
    pub(super) resolver_override_needed: bool,
    pub(super) outcome: &'static str,
    pub(super) encrypted_addresses: Vec<String>,
    pub(super) encrypted_ips: Vec<IpAddr>,
}

pub(super) fn classify_target_dns_integrity(
    system_targets: &[SocketAddr],
    system_resolution_failed: bool,
    system_error_kind: Option<DnsResolveError>,
    system_latency_ms: &str,
    oracle_assessment: &DnsOracleAssessment<DnsOracleResponse>,
) -> Option<StrategyDnsClassification> {
    let encrypted_addresses =
        oracle_assessment.selected.as_ref().map(|selected| selected.value.addresses.clone()).unwrap_or_default();
    let encrypted_ips = encrypted_addresses.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect::<Vec<_>>();
    if oracle_assessment.trust.allows_tampering_classification() && encrypted_ips.is_empty() {
        return None;
    }

    let (resolver_override_needed, outcome) = if system_resolution_failed
        && oracle_assessment.trust.allows_tampering_classification()
    {
        if system_error_kind == Some(DnsResolveError::Nxdomain) {
            (true, "dns_nxdomain_mismatch")
        } else {
            (true, "dns_system_resolution_failed")
        }
    } else if system_resolution_failed {
        // Both failed or the encrypted oracle was not trusted enough to prove
        // resolver interference. Skip to avoid false positives.
        return None;
    } else if !oracle_assessment.trust.allows_tampering_classification() {
        (false, "dns_oracle_unavailable")
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

    Some(StrategyDnsClassification { resolver_override_needed, outcome, encrypted_addresses, encrypted_ips })
}
