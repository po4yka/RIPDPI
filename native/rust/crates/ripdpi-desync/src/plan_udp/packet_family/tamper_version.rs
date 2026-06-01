use ripdpi_config::DesyncGroup;
use ripdpi_packets::{QuicInitialBrowserProfile, QuicInitialPacketLayout, tamper_quic_version};

use super::super::quic::{NormalizedQuicPlannerInput, packetize_browser_like_quic_initial};

pub(super) fn build_quic_fake_version_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let layout = QuicInitialPacketLayout::contiguous(normalized_quic.map_or(1200, |quic| quic.datagram_len.max(1200)));
    let Some(seed_packet) =
        packetize_browser_like_quic_initial(group, normalized_quic, QuicInitialBrowserProfile::ChromeAndroid, layout)
    else {
        return Vec::new();
    };
    let Some(packet) = tamper_quic_version(&seed_packet, group.actions.quic_fake_version) else {
        return Vec::new();
    };
    vec![packet; count as usize]
}

pub(super) fn build_quic_version_negotiation_decoy_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    let Some(seed_packet) = packetize_browser_like_quic_initial(
        group,
        Some(normalized_quic),
        QuicInitialBrowserProfile::ChromeAndroid,
        QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200)),
    ) else {
        return Vec::new();
    };
    let version = normalized_quic.version ^ 0x0f0f_0f0f;
    let Some(packet) = tamper_quic_version(&seed_packet, version) else {
        return Vec::new();
    };
    vec![packet; count.max(1) as usize]
}
