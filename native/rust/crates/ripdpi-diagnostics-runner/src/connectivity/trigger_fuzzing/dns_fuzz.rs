use std::collections::BTreeSet;

use super::summary::{TriggerFuzzOutcome, append_trigger_fuzzing_summary};
use crate::connectivity::adapters::dns::{build_dns_query_with_type, parse_dns_response};
use crate::connectivity::adapters::transport::{
    TransportConfig, relay_udp_direct, relay_udp_via_socks5, resolve_first_socket_addr,
};
use crate::connectivity::adapters::util::now_ms;
use crate::types::{DnsTarget, ProbeDetail};

const MAX_DNS_FUZZ_VARIANTS: usize = 3;

pub(crate) fn append_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DnsTarget,
    transport: &TransportConfig,
    baseline_outcome: &str,
    encrypted_result: &Result<Vec<String>, String>,
) {
    let udp_server = target.udp_server.as_deref().unwrap_or(crate::connectivity::adapters::util::DEFAULT_DNS_SERVER);
    let variants = [
        (
            "uppercase_qname",
            "qname_case",
            build_dns_query_with_type(&target.domain.to_ascii_uppercase(), dns_query_id(1), 1),
            dns_query_id(1),
        ),
        (
            "mixedcase_qname",
            "qname_case",
            build_dns_query_with_type(&alternating_case(&target.domain), dns_query_id(2), 1),
            dns_query_id(2),
        ),
        ("edns0_opt", "edns0", build_dns_query_with_edns0(&target.domain, dns_query_id(3)), dns_query_id(3)),
    ];

    let mut outcomes = Vec::new();
    for (id, field, packet, query_id) in variants.into_iter().take(MAX_DNS_FUZZ_VARIANTS) {
        let Ok(packet) = packet else {
            continue;
        };

        let variant_result = execute_variant(udp_server, transport, &packet, query_id);
        let outcome = classify_variant_outcome(&variant_result, encrypted_result);
        let detail = variant_result.as_ref().map_or_else(Clone::clone, |addresses| addresses.join("|"));
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }

    append_trigger_fuzzing_summary(details, "dnsFuzz", baseline_outcome, &outcomes);
}

fn execute_variant(
    server: &str,
    transport: &TransportConfig,
    packet: &[u8],
    query_id: u16,
) -> Result<Vec<String>, String> {
    let server_addr = resolve_first_socket_addr(server)?;
    let response = match transport {
        TransportConfig::Direct { .. } => relay_udp_direct(server_addr, packet).map(|(bytes, _)| bytes),
        TransportConfig::Socks5 { host, port, credentials } => {
            relay_udp_via_socks5(host, *port, server_addr, packet, credentials.as_ref()).map(|(bytes, _)| bytes)
        }
    }?;

    parse_dns_response(&response, query_id)
}

fn classify_variant_outcome(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
) -> String {
    match (udp_result, encrypted_result) {
        (Ok(udp), Ok(encrypted)) => {
            let udp_set = udp.iter().cloned().collect::<BTreeSet<_>>();
            let encrypted_set = encrypted.iter().cloned().collect::<BTreeSet<_>>();
            if udp_set == encrypted_set {
                "dns_match".to_string()
            } else if !udp_set.is_disjoint(&encrypted_set) {
                "dns_compatible_divergence".to_string()
            } else {
                "dns_sinkhole_substitution".to_string()
            }
        }
        (Err(err), Ok(_)) if err == "dns_nxdomain" => "dns_nxdomain_mismatch".to_string(),
        (Err(_), Ok(_)) => "udp_blocked".to_string(),
        (Ok(_), Err(_)) => "dns_oracle_unavailable".to_string(),
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
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
