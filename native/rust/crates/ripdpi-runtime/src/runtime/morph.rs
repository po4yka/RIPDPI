use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, EntropyMode, QuicFakeProfile, TcpChainStepKind};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_packets::is_tls_client_hello;
use ripdpi_proxy_config::ProxyMorphPolicy;

use super::state::RuntimeState;

pub(super) fn current_morph_policy(state: &RuntimeState) -> Option<&ProxyMorphPolicy> {
    state.runtime_context.as_ref()?.morph_policy.as_ref()
}

pub(super) fn apply_tcp_morph_policy_to_hints(
    state: &RuntimeState,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    let Some(policy) = current_morph_policy(state) else {
        return hints;
    };
    let mut morphed = hints;
    if policy.entropy_target_permil > 0 {
        morphed.entropy_mode = Some(EntropyMode::Popcount);
    }
    morphed
}

pub(super) fn apply_udp_morph_policy_to_hints(
    state: &RuntimeState,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    let Some(policy) = current_morph_policy(state) else {
        return hints;
    };
    let mut morphed = hints;
    if let Some(profile) = map_udp_burst_profile(policy.quic_burst_profile.as_str()) {
        morphed.udp_burst_profile = Some(profile);
    }
    if let Some(profile) = map_quic_fake_profile(policy.fake_packet_shape_profile.as_str()) {
        morphed.quic_fake_profile = Some(profile);
    }
    morphed
}

pub(super) fn apply_tcp_morph_policy_to_group(
    state: &RuntimeState,
    group: &DesyncGroup,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> DesyncGroup {
    let Some(policy) = current_morph_policy(state) else {
        return group.clone();
    };
    let mut morphed = group.clone();
    let is_tls = is_tls_client_hello(payload);

    if policy.entropy_target_permil > 0 {
        morphed.actions.entropy_mode = EntropyMode::Popcount;
        morphed.actions.entropy_padding_target_permil = Some(policy.entropy_target_permil.max(0) as u32);
    }
    if policy.padding_envelope_max > 0 {
        morphed.actions.entropy_padding_max = policy.padding_envelope_max.max(0) as u32;
    }
    if policy.first_flight_size_min > 0 || policy.first_flight_size_max > 0 {
        morphed.actions.fake_tls_size = select_first_flight_size(policy, payload.len(), hints);
    }

    let cadence = select_tcp_cadence(policy, is_tls);
    if !cadence.is_empty() {
        for (index, step) in
            morphed.actions.tcp_chain.iter_mut().filter(|step| step_supports_cadence(step.kind)).enumerate()
        {
            step.inter_segment_delay_ms = cadence[index % cadence.len()];
        }
    }

    morphed
}

pub(super) fn emit_morph_hint_applied(state: &RuntimeState, target: SocketAddr, family: Option<String>) {
    let Some(telemetry) = &state.telemetry else {
        return;
    };
    let Some(policy) = current_morph_policy(state) else {
        return;
    };
    let Some(family) = family.as_deref().filter(|value| !value.is_empty()) else {
        return;
    };
    telemetry.on_morph_hint_applied(target, policy.id.as_str(), family);
}

pub(super) fn emit_morph_rollback(state: &RuntimeState, target: SocketAddr, reason: impl AsRef<str>) {
    let Some(telemetry) = &state.telemetry else {
        return;
    };
    let Some(policy) = current_morph_policy(state) else {
        return;
    };
    let reason = reason.as_ref();
    if reason.is_empty() {
        return;
    }
    telemetry.on_morph_rollback(target, policy.id.as_str(), reason);
}

pub(super) fn tcp_morph_hint_family(
    state: &RuntimeState,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> Option<String> {
    let policy = current_morph_policy(state)?;
    let cadence = if is_tls_client_hello(payload) { "tls" } else { "tcp" };
    let record_profile = match hints.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Balanced => "balanced",
        AdaptiveTlsRandRecProfile::Tight => "tight",
        AdaptiveTlsRandRecProfile::Wide => "wide",
    };
    let entropy = if policy.entropy_target_permil > 0 { "entropy" } else { "plain" };
    Some(format!("{cadence}:{record_profile}:{entropy}"))
}

pub(super) fn udp_morph_hint_family(state: &RuntimeState, hints: AdaptivePlannerHints) -> Option<String> {
    let _policy = current_morph_policy(state)?;
    let burst = match hints.udp_burst_profile.unwrap_or(AdaptiveUdpBurstProfile::Balanced) {
        AdaptiveUdpBurstProfile::Balanced => "balanced",
        AdaptiveUdpBurstProfile::Conservative => "conservative",
        AdaptiveUdpBurstProfile::Aggressive => "aggressive",
    };
    let fake = match hints.quic_fake_profile.unwrap_or(QuicFakeProfile::Disabled) {
        QuicFakeProfile::Disabled => "disabled",
        QuicFakeProfile::CompatDefault => "compat",
        QuicFakeProfile::RealisticInitial => "realistic",
    };
    Some(format!("quic:{burst}:{fake}"))
}

fn select_first_flight_size(policy: &ProxyMorphPolicy, payload_len: usize, hints: AdaptivePlannerHints) -> i32 {
    let min = policy.first_flight_size_min.max(0);
    let max = policy.first_flight_size_max.max(min);
    if max == 0 {
        return payload_len as i32;
    }
    let bucket = match hints.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Tight => min,
        AdaptiveTlsRandRecProfile::Wide => max,
        AdaptiveTlsRandRecProfile::Balanced => min + ((max - min) / 2),
    };
    let padding = select_padding_envelope(policy, hints);
    bucket.max((payload_len as i32).saturating_add(padding)).clamp(min, max)
}

fn select_padding_envelope(policy: &ProxyMorphPolicy, hints: AdaptivePlannerHints) -> i32 {
    let min = policy.padding_envelope_min.max(0);
    let max = policy.padding_envelope_max.max(min);
    if max == min {
        return max;
    }
    match hints.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Tight => min,
        AdaptiveTlsRandRecProfile::Wide => max,
        AdaptiveTlsRandRecProfile::Balanced => min + ((max - min) / 2),
    }
}

fn select_tcp_cadence(policy: &ProxyMorphPolicy, is_tls: bool) -> Vec<u32> {
    let source = if is_tls && !policy.tls_burst_cadence_ms.is_empty() {
        &policy.tls_burst_cadence_ms
    } else {
        &policy.tcp_burst_cadence_ms
    };
    source.iter().map(|value| (*value).max(0) as u32).collect()
}

fn step_supports_cadence(kind: TcpChainStepKind) -> bool {
    matches!(
        kind,
        TcpChainStepKind::Split
            | TcpChainStepKind::Disorder
            | TcpChainStepKind::MultiDisorder
            | TcpChainStepKind::Fake
            | TcpChainStepKind::FakeSplit
            | TcpChainStepKind::FakeDisorder
            | TcpChainStepKind::HostFake
            | TcpChainStepKind::Oob
            | TcpChainStepKind::Disoob
            | TcpChainStepKind::TlsRec
            | TcpChainStepKind::TlsRandRec
    )
}

fn map_udp_burst_profile(value: &str) -> Option<AdaptiveUdpBurstProfile> {
    match value.trim() {
        "compat_burst" => Some(AdaptiveUdpBurstProfile::Conservative),
        "balanced_burst" => Some(AdaptiveUdpBurstProfile::Balanced),
        "realistic_burst" => Some(AdaptiveUdpBurstProfile::Aggressive),
        _ => None,
    }
}

fn map_quic_fake_profile(value: &str) -> Option<QuicFakeProfile> {
    match value.trim() {
        "compat_default" => Some(QuicFakeProfile::CompatDefault),
        "realistic_initial" => Some(QuicFakeProfile::RealisticInitial),
        "disabled" => Some(QuicFakeProfile::Disabled),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
    use crate::adaptive_tuning::AdaptivePlannerResolver;
    use crate::retry_stealth::RetryPacer;
    use crate::runtime::state::RuntimeState;
    use crate::runtime_policy::RuntimePolicy;
    use crate::strategy_evolver::StrategyEvolver;
    use crate::sync::{Arc, AtomicBool, AtomicUsize, Mutex};
    use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind};
    use ripdpi_packets::DEFAULT_FAKE_TLS;
    use ripdpi_proxy_config::ProxyRuntimeContext;

    fn state_with_policy(policy: ProxyMorphPolicy) -> RuntimeState {
        RuntimeState {
            config: Arc::new(RuntimeConfig::default()),
            cache: Arc::new(Mutex::new(RuntimePolicy::default())),
            adaptive_fake_ttl: Arc::new(Mutex::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: Arc::new(Mutex::new(AdaptivePlannerResolver::default())),
            retry_stealth: Arc::new(crate::sync::RwLock::new(RetryPacer::default())),
            strategy_evolver: Arc::new(crate::sync::RwLock::new(StrategyEvolver::new(false, 0.0))),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry: None,
            runtime_context: Some(ProxyRuntimeContext {
                encrypted_dns: None,
                protect_path: None,
                preferred_edges: std::collections::BTreeMap::default(),
                direct_path_capabilities: Vec::new(),
                morph_policy: Some(policy),
            }),
            control: None,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(crate::runtime::reprobe::ReprobeTracker::new()),
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
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
            TcpChainStep::new(TcpChainStepKind::TlsRec, ripdpi_config::OffsetExpr::tls_host(0)),
            TcpChainStep::new(TcpChainStepKind::Fake, ripdpi_config::OffsetExpr::host(1)),
        ];
        let hints =
            AdaptivePlannerHints { tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide), ..Default::default() };

        let morphed = apply_tcp_morph_policy_to_group(&state, &group, DEFAULT_FAKE_TLS, hints);

        assert_eq!(morphed.actions.fake_tls_size, 640);
        assert_eq!(morphed.actions.entropy_mode, EntropyMode::Popcount);
        assert_eq!(morphed.actions.entropy_padding_target_permil, Some(3400));
        assert_eq!(morphed.actions.entropy_padding_max, 64);
        assert_eq!(morphed.actions.tcp_chain[0].inter_segment_delay_ms, 0);
        assert_eq!(morphed.actions.tcp_chain[1].inter_segment_delay_ms, 8);
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
