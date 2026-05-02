use ripdpi_config::QuicFakeProfile;
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_proxy_config::ProxyMorphPolicy;
use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;

pub(super) fn tcp_morph_hint_family(
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

pub(super) fn udp_morph_hint_family(policy: Option<&ProxyMorphPolicy>, hints: AdaptivePlannerHints) -> Option<String> {
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
