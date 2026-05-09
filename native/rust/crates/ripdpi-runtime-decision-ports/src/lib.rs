//! Selected-decision ports for socket runtime execution.
//!
//! `ripdpi-proxy-runtime` should depend on this narrow adapter instead of the
//! policy/adaptive engines directly. The engines remain behind these port traits
//! and selected-route data contracts.

pub use ripdpi_runtime_adaptive::morph_policy::{
    apply_tcp_morph_policy_to_group, apply_tcp_morph_policy_to_hints, apply_udp_morph_policy_to_hints,
    tcp_morph_hint_family, udp_morph_hint_family,
};
pub use ripdpi_runtime_adaptive::strategy_context::{
    direct_path_capability_for_route, merge_udp_hints_with_capability, network_scope_key,
};
pub use ripdpi_runtime_adaptive::{
    AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, PreferredTargets, RetryPacingPort,
};
pub use ripdpi_runtime_policy::{
    classify_response_failure, response_requires_dns_tampering_evidence, ConnectionRoute, DirectPathLearningObserver,
    DirectPathLearningPort, DnsTamperingEvidence, ExtractedHost, HostAutolearnEvent, HostAutolearnState, HostSource,
    PolicyPort, RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};

#[doc(hidden)]
pub use ripdpi_runtime_policy::{
    extract_host, extract_host_info, group_requires_payload, is_tls_client_hello_payload, route_matches_payload,
};
