//! Selected-decision ports for socket runtime execution.
//!
//! `ripdpi-proxy-runtime` should depend on this narrow adapter instead of the
//! policy/adaptive engines directly. The engines remain behind these port traits
//! and selected-route data contracts.

mod snapshot;

pub use snapshot::{
    AdaptiveDecisionSnapshot, DesyncDecisionSnapshot, DirectPathDnsMode, DirectPathQuicMode, DnsDecisionSnapshot,
    ProxyDecisionSnapshot, QuicDecisionSnapshot, RelayDecisionSnapshot, RoutingDecisionSnapshot,
    RuntimeDecisionSnapshot, StrategyPackDecisionSnapshot, UiDecisionSnapshot, WarpDecisionSnapshot,
};

pub use ripdpi_runtime_adaptive::{
    AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, PreferredTargets, RetryPacingPort,
};
pub use ripdpi_runtime_policy::{
    ConnectionRoute, DirectPathLearningObserver, DirectPathLearningPort, DnsTamperingEvidence, ExtractedHost,
    HostAutolearnEvent, HostAutolearnState, HostSource, PolicyPort, RetrySelectionPenalty, RouteAdvance,
    TransportProtocol,
};

pub use ripdpi_runtime_adaptive::strategy_context::{
    direct_path_capability_for_route, merge_udp_hints_with_capability, network_scope_key,
};
pub use ripdpi_runtime_policy::{
    classify_response_failure, extract_host, extract_host_info, group_requires_payload, is_tls_client_hello_payload,
    response_requires_dns_tampering_evidence, route_matches_payload,
};

pub fn apply_udp_morph_policy_to_hints(
    policy: Option<&ripdpi_proxy_config::ProxyMorphPolicy>,
    hints: ripdpi_desync::AdaptivePlannerHints,
) -> ripdpi_desync::AdaptivePlannerHints {
    ripdpi_runtime_adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
}

pub fn apply_tcp_morph_policy_to_group(
    policy: Option<&ripdpi_proxy_config::ProxyMorphPolicy>,
    group: &ripdpi_config::DesyncGroup,
    payload: &[u8],
    hints: ripdpi_desync::AdaptivePlannerHints,
) -> ripdpi_config::DesyncGroup {
    ripdpi_runtime_adaptive::morph_policy::apply_tcp_morph_policy_to_group(policy, group, payload, hints)
}

pub fn tcp_morph_hint_family(
    policy: Option<&ripdpi_proxy_config::ProxyMorphPolicy>,
    payload: &[u8],
    hints: ripdpi_desync::AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_adaptive::morph_policy::tcp_morph_hint_family(policy, payload, hints)
}

pub fn udp_morph_hint_family(
    policy: Option<&ripdpi_proxy_config::ProxyMorphPolicy>,
    hints: ripdpi_desync::AdaptivePlannerHints,
) -> Option<String> {
    ripdpi_runtime_adaptive::morph_policy::udp_morph_hint_family(policy, hints)
}
