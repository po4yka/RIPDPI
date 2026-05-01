use crate::types::{DnsObservationFact, ObservationKind, ProbeObservation, ProbeResult};

use super::common::{base_observation, detail_list, detail_value, dns_status};

pub(crate) fn build_dns_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Dns);
    observation.dns = Some(DnsObservationFact {
        domain: result.target.clone(),
        status: dns_status(&result.outcome),
        udp_addresses: detail_list(result, "udpAddresses"),
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
    let encrypted = detail_list(result, "encryptedAddresses");
    if encrypted.is_empty() {
        detail_list(result, "dohAddresses")
    } else {
        encrypted
    }
}
