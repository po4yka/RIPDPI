use ripdpi_config::QuicFakeProfile;
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
use ripdpi_proxy_config::ProxyDirectPathCapability;

use super::direct_path_capability::{capability_preserves_udp_transport, capability_requires_desync_fallback};

pub fn merge_udp_hints_with_capability(
    mut hints: AdaptivePlannerHints,
    capability: Option<&ProxyDirectPathCapability>,
) -> AdaptivePlannerHints {
    let Some(capability) = capability else {
        return hints;
    };
    if capability_preserves_udp_transport(capability) {
        return hints;
    }
    let should_conservatively_fallback = capability_requires_desync_fallback(capability)
        || capability.udp_usable == Some(false)
        || capability.quic_usable == Some(false);
    if should_conservatively_fallback {
        hints.udp_burst_profile = Some(AdaptiveUdpBurstProfile::Aggressive);
        hints.quic_fake_profile = Some(QuicFakeProfile::CompatDefault);
        return hints;
    }
    if capability.quic_usable == Some(true) {
        hints.udp_burst_profile.get_or_insert(AdaptiveUdpBurstProfile::Conservative);
    }
    hints
}
