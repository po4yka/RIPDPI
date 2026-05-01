mod circumvention;
mod common;
mod dns;
mod domain;
mod quic;
mod service;
mod strategy;
mod tcp;
mod telegram;
mod throughput;

use std::collections::HashMap;

use crate::types::{ProbeObservation, ProbeResult};

pub use common::{detail_list, detail_value, transport_failure};

pub const ENGINE_ANALYSIS_VERSION: &str = "observations_v1";

pub fn observations_for_results(results: &[ProbeResult]) -> Vec<ProbeObservation> {
    let mut observations: Vec<ProbeObservation> = results.iter().filter_map(observation_for_probe).collect();
    annotate_dns_injection_pools(&mut observations);
    observations
}

pub fn observation_for_probe(result: &ProbeResult) -> Option<ProbeObservation> {
    match result.probe_type.as_str() {
        "dns_integrity" => Some(dns::build_dns_observation(result)),
        "domain_reachability" => Some(domain::build_domain_observation(result)),
        "tcp_fat_header" => Some(tcp::build_tcp_observation(result)),
        "quic_reachability" => Some(quic::build_quic_observation(result)),
        "service_reachability" => Some(service::build_service_observation(result)),
        "circumvention_reachability" => Some(circumvention::build_circumvention_observation(result)),
        "telegram_availability" => Some(telegram::build_telegram_observation(result)),
        "throughput_window" => Some(throughput::build_throughput_observation(result)),
        "strategy_http" | "strategy_https" | "strategy_quic" => Some(strategy::build_strategy_observation(result)),
        _ => None,
    }
}

/// Cross-domain analysis: when 3+ domains share the same forged IP, mark
/// them as belonging to a middlebox redirect pool (`dns_injection_pool_detected`).
fn annotate_dns_injection_pools(observations: &mut [ProbeObservation]) {
    let mut ip_to_indices: HashMap<String, Vec<usize>> = HashMap::new();
    for (idx, obs) in observations.iter().enumerate() {
        if let Some(dns) = &obs.dns {
            if let Some(forged) = &dns.forged_addresses {
                for ip in forged {
                    ip_to_indices.entry(ip.clone()).or_default().push(idx);
                }
            }
        }
    }

    for (pool_ip, indices) in &ip_to_indices {
        if indices.len() >= 3 {
            for &idx in indices {
                if let Some(dns) = &mut observations[idx].dns {
                    dns.forged_address_pool = Some(pool_ip.clone());
                }
                if !observations[idx].evidence.contains(&"dns_injection_pool_detected".to_string()) {
                    observations[idx].evidence.push("dns_injection_pool_detected".to_string());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests;
