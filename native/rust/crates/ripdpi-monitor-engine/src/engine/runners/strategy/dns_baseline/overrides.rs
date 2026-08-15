use std::collections::HashMap;
use std::net::IpAddr;

use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};

pub(super) fn record_dns_override_targets(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    encrypted_ip_overrides: &[(String, IpAddr)],
) {
    if encrypted_ip_overrides.is_empty() {
        return;
    }
    let overrides = host_override_map(encrypted_ip_overrides);
    let mut domain_targets = plan.request.domain_targets.clone();
    for target in domain_targets.iter_mut().filter(|target| target.connect_ip.is_none()) {
        target.connect_ip = overrides.get(target.host.as_str()).cloned();
    }
    let mut quic_targets = plan.request.quic_targets.clone();
    for target in quic_targets.iter_mut().filter(|target| target.connect_ip.is_none()) {
        target.connect_ip = overrides.get(target.host.as_str()).cloned();
    }
    runtime.strategy.dns_override_domain_targets = Some(domain_targets);
    runtime.strategy.dns_override_quic_targets = Some(quic_targets);
}

fn host_override_map(encrypted_ip_overrides: &[(String, IpAddr)]) -> HashMap<&str, String> {
    let mut overrides = HashMap::new();
    for (host, ip) in encrypted_ip_overrides {
        overrides.entry(host.as_str()).or_insert_with(|| ip.to_string());
    }
    overrides
}
