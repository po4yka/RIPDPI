use ripdpi_config::{DesyncGroup, EntropyMode, QuicFakeProfile, TcpChainStepKind};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_proxy_config::ProxyMorphPolicy;
use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;

pub fn apply_tcp_morph_policy_to_hints(
    policy: Option<&ProxyMorphPolicy>,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    let Some(policy) = policy else {
        return hints;
    };
    let mut morphed = hints;
    if policy.entropy_target_permil > 0 {
        morphed.entropy_mode = Some(EntropyMode::Popcount);
    }
    morphed
}

pub fn apply_udp_morph_policy_to_hints(
    policy: Option<&ProxyMorphPolicy>,
    hints: AdaptivePlannerHints,
) -> AdaptivePlannerHints {
    let Some(policy) = policy else {
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

pub fn apply_tcp_morph_policy_to_group(
    policy: Option<&ProxyMorphPolicy>,
    group: &DesyncGroup,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> DesyncGroup {
    let Some(policy) = policy else {
        return group.clone();
    };
    let mutations = TcpMorphMutations::from_policy(policy, payload, hints);
    mutations.apply_to(group)
}

pub fn tcp_morph_hint_family(
    policy: Option<&ProxyMorphPolicy>,
    payload: &[u8],
    hints: AdaptivePlannerHints,
) -> Option<String> {
    let policy = policy?;
    let cadence = if is_tls_client_hello_payload(payload) { "tls" } else { "tcp" };
    let record_profile = match hints.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Balanced => "balanced",
        AdaptiveTlsRandRecProfile::Tight => "tight",
        AdaptiveTlsRandRecProfile::Wide => "wide",
    };
    let entropy = if policy.entropy_target_permil > 0 { "entropy" } else { "plain" };
    Some(format!("{cadence}:{record_profile}:{entropy}"))
}

pub fn udp_morph_hint_family(policy: Option<&ProxyMorphPolicy>, hints: AdaptivePlannerHints) -> Option<String> {
    let _policy = policy?;
    let burst = match hints.udp_burst_profile.unwrap_or(AdaptiveUdpBurstProfile::Balanced) {
        AdaptiveUdpBurstProfile::Balanced => "balanced",
        AdaptiveUdpBurstProfile::Conservative => "conservative",
        AdaptiveUdpBurstProfile::Aggressive => "aggressive",
    };
    let fake = match hints.quic_fake_profile.unwrap_or(QuicFakeProfile::Disabled) {
        QuicFakeProfile::Disabled => "disabled",
        QuicFakeProfile::CompatDefault => "compat",
        QuicFakeProfile::RealisticInitial => "realistic",
        _ => "disabled",
    };
    Some(format!("quic:{burst}:{fake}"))
}

struct TcpMorphMutations {
    entropy_target_permil: Option<u32>,
    entropy_padding_max: Option<u32>,
    fake_tls_size: Option<i32>,
    cadence: Vec<u32>,
}

impl TcpMorphMutations {
    fn from_policy(policy: &ProxyMorphPolicy, payload: &[u8], hints: AdaptivePlannerHints) -> Self {
        let entropy_target_permil =
            (policy.entropy_target_permil > 0).then(|| policy.entropy_target_permil.max(0) as u32);
        let entropy_padding_max = (policy.padding_envelope_max > 0).then(|| policy.padding_envelope_max.max(0) as u32);
        let fake_tls_size = (policy.first_flight_size_min > 0 || policy.first_flight_size_max > 0)
            .then(|| select_first_flight_size(policy, payload.len(), hints));
        let cadence = select_tcp_cadence(policy, is_tls_client_hello_payload(payload));
        Self { entropy_target_permil, entropy_padding_max, fake_tls_size, cadence }
    }

    fn apply_to(&self, group: &DesyncGroup) -> DesyncGroup {
        let mut morphed = group.clone();
        if let Some(target) = self.entropy_target_permil {
            morphed.actions.entropy_mode = EntropyMode::Popcount;
            morphed.actions.entropy_padding_target_permil = Some(target);
        }
        if let Some(max) = self.entropy_padding_max {
            morphed.actions.entropy_padding_max = max;
        }
        if let Some(size) = self.fake_tls_size {
            morphed.actions.fake_tls_size = size;
        }
        if !self.cadence.is_empty() {
            for (index, step) in
                morphed.actions.tcp_chain.iter_mut().filter(|step| step_supports_cadence(step.kind)).enumerate()
            {
                step.inter_segment_delay_ms = self.cadence[index % self.cadence.len()];
            }
        }
        morphed
    }
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
