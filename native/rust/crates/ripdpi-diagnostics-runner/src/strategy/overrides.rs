use std::net::IpAddr;

use crate::types::DomainTarget;

pub(super) fn collect_encrypted_ip_override(
    encrypted_ip_overrides: &mut Vec<(String, IpAddr)>,
    target: &DomainTarget,
    encrypted_ips: &[IpAddr],
) {
    // Collect the first encrypted IP as an override for strategy probing.
    if let Some(&override_ip) = encrypted_ips.first()
        && !encrypted_ip_overrides.iter().any(|(host, _)| host == &target.host)
    {
        encrypted_ip_overrides.push((target.host.clone(), override_ip));
    }
}
