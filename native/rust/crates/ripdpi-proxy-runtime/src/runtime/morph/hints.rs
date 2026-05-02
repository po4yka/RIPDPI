use ripdpi_config::{EntropyMode, QuicFakeProfile};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
use ripdpi_proxy_config::ProxyMorphPolicy;

pub(super) fn apply_tcp_morph_policy_to_hints(
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

pub(super) fn apply_udp_morph_policy_to_hints(
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
