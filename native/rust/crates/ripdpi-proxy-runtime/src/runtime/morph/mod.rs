use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use ripdpi_proxy_runtime_adapter::model::desync::AdaptivePlannerHints;
use ripdpi_proxy_runtime_adapter::model::proxy_config::{self as morph_adapter, ProxyMorphPolicy};

use super::state::RuntimeState;

pub(super) fn current_morph_policy(state: &RuntimeState) -> Option<&ProxyMorphPolicy> {
    morph_adapter::morph_policy(state.runtime_context.as_ref())
}

#[cfg(test)]
pub(super) fn apply_udp_morph_policy_to_hints(
    state: &RuntimeState,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    morph_adapter::apply_udp_morph_policy_to_hints(current_morph_policy(state), hints)
}

pub(super) fn apply_tcp_morph_policy_to_group(
    state: &RuntimeState,
    group: &DesyncGroup,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> DesyncGroup {
    morph_adapter::apply_tcp_morph_policy_to_group(current_morph_policy(state), group, payload, hints)
}

pub(super) fn emit_morph_hint_applied(state: &RuntimeState, target: SocketAddr, family: Option<String>) {
    morph_adapter::emit_morph_hint_applied(state.telemetry.as_deref(), current_morph_policy(state), target, family);
}

pub(super) fn emit_morph_rollback(state: &RuntimeState, target: SocketAddr, reason: impl AsRef<str>) {
    morph_adapter::emit_morph_rollback(state.telemetry.as_deref(), current_morph_policy(state), target, reason);
}

pub(super) fn tcp_morph_hint_family(
    state: &RuntimeState,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> Option<String> {
    morph_adapter::tcp_morph_hint_family(current_morph_policy(state), payload, hints)
}

pub(super) fn udp_morph_hint_family(state: &RuntimeState, hints: AdaptivePlannerHints) -> Option<String> {
    morph_adapter::udp_morph_hint_family(current_morph_policy(state), hints)
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::runtime::state::RuntimeState;
    use ripdpi_packets::DEFAULT_FAKE_TLS;
    use ripdpi_proxy_runtime_adapter::model::config::{
        DesyncGroup, EntropyMode, QuicFakeProfile, RuntimeConfig, TcpChainStep, TcpChainStepKind,
    };
    use ripdpi_proxy_runtime_adapter::model::desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
    use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext;
    use ripdpi_runtime_decision_ports::policy::RuntimePolicy;

    fn state_with_policy(policy: ProxyMorphPolicy) -> RuntimeState {
        RuntimeState::test_with_runtime_policy(
            RuntimeConfig::default(),
            Some(ProxyRuntimeContext {
                encrypted_dns: None,
                protect_path: None,
                preferred_edges: std::collections::BTreeMap::default(),
                direct_path_capabilities: Vec::new(),
                morph_policy: Some(policy),
            }),
            RuntimePolicy::default(),
        )
    }

    #[test]
    fn tcp_morph_policy_updates_group_actions_and_cadence() {
        let state = state_with_policy(ProxyMorphPolicy {
            id: "balanced".to_string(),
            first_flight_size_min: 320,
            first_flight_size_max: 640,
            padding_envelope_min: 16,
            padding_envelope_max: 64,
            entropy_target_permil: 3400,
            tcp_burst_cadence_ms: vec![0, 12, 24],
            tls_burst_cadence_ms: vec![0, 8],
            quic_burst_profile: "compat_burst".to_string(),
            fake_packet_shape_profile: "compat_default".to_string(),
        });
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![
            TcpChainStep::new(
                TcpChainStepKind::TlsRec,
                ripdpi_proxy_runtime_adapter::model::config::OffsetExpr::tls_host(0),
            ),
            TcpChainStep::new(TcpChainStepKind::Fake, ripdpi_proxy_runtime_adapter::model::config::OffsetExpr::host(1)),
        ];
        let hints =
            AdaptivePlannerHints { tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide), ..Default::default() };

        let morphed = apply_tcp_morph_policy_to_group(&state, &group, DEFAULT_FAKE_TLS, hints);

        assert_eq!(morphed.actions.fake_tls_size, 640);
        assert_eq!(morphed.actions.entropy_mode, EntropyMode::Popcount);
        assert_eq!(morphed.actions.entropy_padding_target_permil, Some(3400));
        assert_eq!(morphed.actions.entropy_padding_max, 64);
        assert_eq!(morphed.actions.tcp_chain[0].inter_segment_delay_ms(), 0);
        assert_eq!(morphed.actions.tcp_chain[1].inter_segment_delay_ms(), 8);
    }

    #[test]
    fn udp_morph_policy_overrides_hint_profiles() {
        let state = state_with_policy(ProxyMorphPolicy {
            id: "balanced".to_string(),
            first_flight_size_min: 0,
            first_flight_size_max: 0,
            padding_envelope_min: 0,
            padding_envelope_max: 0,
            entropy_target_permil: 0,
            tcp_burst_cadence_ms: Vec::new(),
            tls_burst_cadence_ms: Vec::new(),
            quic_burst_profile: "realistic_burst".to_string(),
            fake_packet_shape_profile: "realistic_initial".to_string(),
        });

        let hints = apply_udp_morph_policy_to_hints(&state, AdaptivePlannerHints::default());

        assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Aggressive));
        assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
    }
}
