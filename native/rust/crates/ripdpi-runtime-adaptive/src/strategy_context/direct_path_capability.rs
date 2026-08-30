use std::collections::HashSet;
use std::net::SocketAddr;

use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_decision_ports::adaptive_ports::PreferredTargetSuppressionReason;
use ripdpi_runtime_policy::direct_path_learning::direct_path_ip_set_digest;
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use super::authority::{
    direct_path_authority_candidates, direct_path_authority_candidates_for_targets,
    direct_path_hostname_authority_candidates,
};

pub fn direct_path_capability_for_route<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    target: SocketAddr,
) -> Option<&'a ProxyDirectPathCapability> {
    let capabilities = runtime_context?.direct_path_capabilities.as_slice();
    let candidates = direct_path_authority_candidates(host, target).into_iter().collect::<HashSet<_>>();
    capabilities.iter().find(|capability| candidates.contains(capability.authority.as_str()))
}

pub fn direct_path_capability_for_targets<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> Option<&'a ProxyDirectPathCapability> {
    let capabilities = runtime_context?.direct_path_capabilities.as_slice();
    let candidates = direct_path_authority_candidates_for_targets(host, targets).into_iter().collect::<HashSet<_>>();
    let ip_set_digest = direct_path_ip_set_digest(targets);
    capabilities.iter().find(|capability| {
        candidates.contains(capability.authority.as_str())
            && (capability.ip_set_digest.trim().is_empty() || capability.ip_set_digest == ip_set_digest)
    })
}

pub(crate) fn direct_path_capability_for_hostname_targets<'a>(
    runtime_context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> Option<&'a ProxyDirectPathCapability> {
    let capabilities = runtime_context?.direct_path_capabilities.as_slice();
    let candidates = direct_path_hostname_authority_candidates(host, targets);
    let ip_set_digest = direct_path_ip_set_digest(targets);
    candidates.iter().find_map(|candidate| {
        capabilities
            .iter()
            .find(|capability| {
                capability.authority.eq_ignore_ascii_case(candidate) && capability.ip_set_digest == ip_set_digest
            })
            .or_else(|| {
                capabilities.iter().find(|capability| {
                    capability.authority.eq_ignore_ascii_case(candidate) && capability.ip_set_digest.trim().is_empty()
                })
            })
    })
}

pub fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
        || (matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
            && !capability_preserves_udp_transport(capability))
        || matches!(capability.outcome.trim().to_ascii_uppercase().as_str(), "OWNED_STACK_ONLY" | "NO_DIRECT_SOLUTION")
}

pub fn capability_preserves_udp_transport(capability: &ProxyDirectPathCapability) -> bool {
    capability.reason_code.as_deref() == Some("NO_TCP_FALLBACK")
}

pub fn capability_udp_clean(capability: &ProxyDirectPathCapability) -> bool {
    if capability_preserves_udp_transport(capability) {
        return true;
    }
    capability.udp_usable != Some(false)
        && capability.quic_usable != Some(false)
        && !matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
}

pub fn capability_blocks_transport(
    capability: &ProxyDirectPathCapability,
    transport: TransportProtocol,
    now_millis: i64,
) -> bool {
    capability_suppression_reason(capability, transport, now_millis).is_some()
}

pub fn capability_suppression_reason(
    capability: &ProxyDirectPathCapability,
    transport: TransportProtocol,
    now_millis: i64,
) -> Option<PreferredTargetSuppressionReason> {
    let cooldown_active = capability.cooldown_until.is_some_and(|value| value > now_millis);
    let outcome = capability.outcome.trim().to_ascii_uppercase();
    if outcome == "OWNED_STACK_ONLY" {
        return Some(PreferredTargetSuppressionReason::OwnedStackRequired);
    }
    if outcome == "NO_DIRECT_SOLUTION" && cooldown_active {
        return Some(PreferredTargetSuppressionReason::NoDirectSolutionCooldown);
    }
    match transport {
        TransportProtocol::Udp => {
            if capability_preserves_udp_transport(capability) {
                return None;
            }
            matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
                .then_some(PreferredTargetSuppressionReason::UdpPolicy)
        }
        TransportProtocol::Tcp => None,
    }
}
