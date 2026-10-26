use std::collections::BTreeSet;

use super::summary::{TriggerFuzzOutcome, append_trigger_fuzzing_summary};
use crate::connectivity::adapters::dns::{
    build_dns_query_with_type, classify_udp_dns_error, is_retryable_udp_dns_error, parse_dns_response_for_query,
};
use crate::connectivity::adapters::transport::{
    TransportConfig, relay_udp_direct, relay_udp_via_socks5, resolve_first_socket_addr,
};
use crate::connectivity::adapters::util::now_ms;
use crate::connectivity::probes::classify_dns_probe_outcome;
use crate::types::{DnsTarget, ProbeDetail, ScanPathMode};

const MAX_DNS_FUZZ_VARIANTS: usize = 3;
/// Attempts per variant for retryable (timeout) errors, so one lost datagram
/// is not reported as a trigger.
const DNS_FUZZ_ATTEMPTS: usize = 2;

pub(crate) fn append_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DnsTarget,
    transport: &TransportConfig,
    path_mode: &ScanPathMode,
    baseline_outcome: &str,
    encrypted_result: &Result<Vec<String>, String>,
) {
    let expected = target.expected_ips.iter().cloned().collect::<BTreeSet<_>>();
    let udp_server = target.udp_server.as_deref().unwrap_or(crate::connectivity::adapters::util::DEFAULT_DNS_SERVER);
    let variants = [
        (
            "uppercase_qname",
            "qname_case",
            build_dns_query_with_type(&target.domain.to_ascii_uppercase(), dns_query_id(1), 1),
        ),
        (
            "mixedcase_qname",
            "qname_case",
            build_dns_query_with_type(&alternating_case(&target.domain), dns_query_id(2), 1),
        ),
        ("edns0_opt", "edns0", build_dns_query_with_edns0(&target.domain, dns_query_id(3))),
    ];

    let mut outcomes = Vec::new();
    for (id, field, packet) in variants.into_iter().take(MAX_DNS_FUZZ_VARIANTS) {
        let Ok(packet) = packet else {
            continue;
        };

        let (variant_result, attempts) = execute_variant_with_retry(udp_server, transport, &packet);
        let outcome = classify_variant_outcome(&variant_result, attempts, encrypted_result, path_mode, &expected);
        let detail = variant_result.as_ref().map_or_else(Clone::clone, |addresses| addresses.join("|"));
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }

    append_trigger_fuzzing_summary(details, "dnsFuzz", baseline_outcome, &outcomes);
}

fn execute_variant(server: &str, transport: &TransportConfig, packet: &[u8]) -> Result<Vec<String>, String> {
    let server_addr = resolve_first_socket_addr(server).map_err(|err| err.to_string())?;
    let response = match transport {
        TransportConfig::Direct { .. } => {
            relay_udp_direct(server_addr, packet).map_err(|err| err.to_string()).map(|(bytes, _)| bytes)
        }
        TransportConfig::Socks5 { host, port, credentials } => {
            relay_udp_via_socks5(host, *port, server_addr, packet, credentials.as_ref())
                .map_err(|err| err.to_string())
                .map(|(bytes, _)| bytes)
        }
    }?;

    parse_dns_response_for_query(&response, packet)
}

/// Classify a variant with the baseline probe's classifier and vocabulary, so
/// an unchanged answer compares equal to the baseline outcome.
fn classify_variant_outcome(
    variant_result: &Result<Vec<String>, String>,
    attempts: usize,
    encrypted_result: &Result<Vec<String>, String>,
    path_mode: &ScanPathMode,
    expected: &BTreeSet<String>,
) -> String {
    let error_kind = variant_result.as_ref().err().map(|error| classify_udp_dns_error(error));
    classify_dns_probe_outcome(variant_result, encrypted_result, path_mode, expected, error_kind, attempts, false)
}

fn execute_variant_with_retry(
    server: &str,
    transport: &TransportConfig,
    packet: &[u8],
) -> (Result<Vec<String>, String>, usize) {
    let mut attempts = 0;
    loop {
        attempts += 1;
        let result = execute_variant(server, transport, packet);
        match &result {
            Err(error) if attempts < DNS_FUZZ_ATTEMPTS && is_retryable_udp_dns_error(classify_udp_dns_error(error)) => {
            }
            _ => return (result, attempts),
        }
    }
}

fn build_dns_query_with_edns0(domain: &str, query_id: u16) -> Result<Vec<u8>, String> {
    let mut packet = build_dns_query_with_type(domain, query_id, 1)?;
    packet[10] = 0;
    packet[11] = 1;
    packet.extend_from_slice(&[0, 0, 41, 0x04, 0xD0, 0, 0, 0, 0, 0, 0]);
    Ok(packet)
}

fn alternating_case(value: &str) -> String {
    let mut upper = true;
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphabetic() {
                let mapped = if upper { ch.to_ascii_uppercase() } else { ch.to_ascii_lowercase() };
                upper = !upper;
                mapped
            } else {
                ch
            }
        })
        .collect()
}

fn dns_query_id(offset: u16) -> u16 {
    (((now_ms() as u16).wrapping_add(offset)) & 0xfffe).max(2)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn classify(variant: Result<Vec<String>, String>) -> String {
        classify_variant_outcome(
            &variant,
            1,
            &Ok(vec!["104.16.132.229".to_string()]),
            &ScanPathMode::RawPath,
            &BTreeSet::new(),
        )
    }

    #[test]
    fn variant_outcomes_use_the_baseline_vocabulary() {
        // Same /24 as the oracle: the baseline calls this a match.
        assert_eq!(classify(Ok(vec!["104.16.132.230".to_string()])), "dns_match");
        // Disjoint public answer: CDN steering, not substitution.
        assert_eq!(classify(Ok(vec!["172.67.1.1".to_string()])), "dns_compatible_divergence");
        // A lost datagram is not UDP blocking.
        assert_eq!(classify(Err("Resource temporarily unavailable: timed out".to_string())), "dns_unavailable");
        assert_eq!(classify(Err("dns_nxdomain".to_string())), "dns_nxdomain_mismatch");
    }
}
