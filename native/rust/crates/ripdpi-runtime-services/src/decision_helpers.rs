use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyMorphPolicy, ProxyRuntimeContext};
use ripdpi_runtime_policy::{DnsTamperingEvidence, ExtractedHost, GeoMatcher, TransportProtocol};

pub fn direct_path_capability_for_route<'a>(
    context: Option<&'a ProxyRuntimeContext>,
    host: Option<&str>,
    target: SocketAddr,
) -> Option<&'a ProxyDirectPathCapability> {
    ripdpi_runtime_adaptive::strategy_context::direct_path_capability_for_route(context, host, target)
}

pub fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    ripdpi_runtime_adaptive::strategy_context::network_scope_key(config)
}

pub fn merge_udp_hints_with_capability(
    hints: AdaptivePlannerHints,
    capability: Option<&ProxyDirectPathCapability>,
) -> AdaptivePlannerHints {
    ripdpi_runtime_adaptive::strategy_context::merge_udp_hints_with_capability(hints, capability)
}

pub fn apply_udp_morph_policy_to_hints(
    policy: Option<&ProxyMorphPolicy>,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    ripdpi_runtime_adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
}

pub fn apply_tcp_morph_policy_to_group(
    policy: Option<&ProxyMorphPolicy>,
    group: &DesyncGroup,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> DesyncGroup {
    ripdpi_runtime_adaptive::morph_policy::apply_tcp_morph_policy_to_group(policy, group, payload, hints)
}

pub fn tcp_morph_hint_family(
    policy: Option<&ProxyMorphPolicy>,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_adaptive::morph_policy::tcp_morph_hint_family(policy, payload, hints)
}

pub fn udp_morph_hint_family(policy: Option<&ProxyMorphPolicy>, hints: AdaptivePlannerHints) -> Option<String> {
    ripdpi_runtime_adaptive::morph_policy::udp_morph_hint_family(policy, hints)
}

pub fn group_requires_payload(group: &DesyncGroup) -> bool {
    ripdpi_runtime_policy::group_requires_payload(group)
}

pub fn route_matches_payload(
    config: &RuntimeConfig,
    group_index: usize,
    dest: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    ripdpi_runtime_policy::route_matches_payload(config, group_index, dest, payload, transport)
}

pub fn route_matches_payload_with_geo(
    config: &RuntimeConfig,
    group_index: usize,
    dest: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
    geo: Option<&dyn GeoMatcher>,
) -> bool {
    match geo {
        Some(geo) => {
            ripdpi_runtime_policy::route_matches_payload_with_geo(config, group_index, dest, payload, transport, geo)
        }
        None => ripdpi_runtime_policy::route_matches_payload(config, group_index, dest, payload, transport),
    }
}

pub fn extract_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    ripdpi_runtime_policy::extract_host(config, payload)
}

pub fn extract_host_info(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    ripdpi_runtime_policy::extract_host_info(config, payload)
}

pub fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
    ripdpi_runtime_policy::is_tls_client_hello_payload(payload)
}

pub fn response_requires_dns_tampering_evidence(request: &[u8], response: &[u8]) -> bool {
    ripdpi_runtime_policy::response_requires_dns_tampering_evidence(request, response)
}

pub fn classify_response_failure(
    request: &[u8],
    response: &[u8],
    dns_evidence: Option<DnsTamperingEvidence<'_>>,
) -> Option<ClassifiedFailure> {
    ripdpi_runtime_policy::classify_response_failure(request, response, dns_evidence)
}
