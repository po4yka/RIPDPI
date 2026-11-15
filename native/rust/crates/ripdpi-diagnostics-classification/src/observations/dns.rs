use crate::types::{DnsObservationFact, ObservationKind, ProbeObservation, ProbeResult};

use super::common::{base_observation, detail_list, detail_value, dns_status};

const PROBE_TYPE: &str = "dns_integrity";

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    (result.probe_type == PROBE_TYPE).then(|| build_dns_observation(result))
}

pub(crate) fn build_dns_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Dns);
    observation.dns = Some(DnsObservationFact {
        domain: result.target.clone(),
        status: dns_status(&result.outcome),
        udp_response: detail_value(result, "udpDnsResponse")
            .and_then(ripdpi_diagnostics_contracts::types::DnsResponseSemantics::from_detail_json),
        encrypted_response: detail_value(result, "encryptedDnsResponse")
            .and_then(ripdpi_diagnostics_contracts::types::DnsResponseSemantics::from_detail_json),
        udp_addresses: address_details(result, "udpAddresses"),
        encrypted_addresses: encrypted_addresses(result),
        udp_latency_ms: detail_value(result, "udpLatencyMs").and_then(|v| v.parse().ok()),
        encrypted_latency_ms: detail_value(result, "encryptedLatencyMs").and_then(|v| v.parse().ok()),
        tampering_score: detail_value(result, "udpTamperingScore").and_then(|v| v.parse().ok()),
        response_anomaly_signals: detail_value(result, "udpAnomalySignals")
            .filter(|v| !v.is_empty())
            .map(|v| v.split('|').map(str::to_string).collect()),
        cname_targets: detail_value(result, "udpCnameTargets")
            .filter(|v| !v.is_empty())
            .map(|v| v.split('|').map(str::to_string).collect()),
        udp_response_size: detail_value(result, "udpResponseSize").and_then(|v| v.parse().ok()),
        udp_has_edns0: detail_value(result, "udpHasEdns0").and_then(|v| v.parse().ok()),
        comparison_score: detail_value(result, "comparisonScore").and_then(|v| v.parse().ok()),
        record_type_mismatch: detail_value(result, "recordTypeMismatch").and_then(|v| v.parse().ok()),
        malformed_pointers: detail_value(result, "malformedPointers").and_then(|v| v.parse().ok()),
        injection_latency_ratio: detail_value(result, "injectionLatencyRatio").and_then(|v| v.parse().ok()),
        forged_addresses: detail_value(result, "forgedAddresses")
            .filter(|v| !v.is_empty())
            .map(|v| v.split(',').map(str::to_string).collect()),
        forged_address_pool: None,
    });
    observation
}

fn encrypted_addresses(result: &ProbeResult) -> Vec<String> {
    let key = if detail_value(result, "encryptedAddresses").is_some() { "encryptedAddresses" } else { "dohAddresses" };
    address_details(result, key)
}

fn address_details(result: &ProbeResult, key: &str) -> Vec<String> {
    if detail_value(result, key)
        .and_then(|value| value.trim_start().get(..6))
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("error:"))
    {
        return Vec::new();
    }
    detail_list(result, key)
        .into_iter()
        .filter_map(|value| value.parse::<IpAddr>().ok())
        .map(|ip| ip.to_string())
        .collect()
}
use std::net::IpAddr;
