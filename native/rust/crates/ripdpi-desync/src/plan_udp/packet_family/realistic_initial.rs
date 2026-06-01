use ripdpi_config::DesyncGroup;
use ripdpi_packets::{QuicInitialBrowserProfile, QuicInitialPacketLayout};

use super::super::quic::{
    NormalizedQuicPlannerInput, packetize_browser_like_quic_initial, quic_browser_profile_for_index,
};

pub(super) fn build_quic_padding_ladder_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout =
                QuicInitialPacketLayout::contiguous(normalized_quic.map_or(1200, |quic| quic.datagram_len.max(1200)));
            layout.extra_tail_padding = 8 * (idx + 1);
            packetize_browser_like_quic_initial(
                group,
                normalized_quic,
                QuicInitialBrowserProfile::ChromeAndroid,
                layout,
            )
        })
        .collect()
}

pub(super) fn build_quic_multi_initial_realistic_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    (0..count.max(2) as usize)
        .filter_map(|idx| {
            let mut layout = QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200));
            layout.extra_tail_padding = idx * 8;
            packetize_browser_like_quic_initial(
                group,
                Some(normalized_quic),
                quic_browser_profile_for_index(idx),
                layout,
            )
        })
        .collect()
}
