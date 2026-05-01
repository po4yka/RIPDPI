use std::collections::HashMap;

use crate::types::ProbeObservation;

/// Cross-domain analysis: when 3+ domains share the same forged IP, mark
/// them as belonging to a middlebox redirect pool (`dns_injection_pool_detected`).
pub(crate) fn annotate_dns_injection_pools(observations: &mut [ProbeObservation]) {
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
