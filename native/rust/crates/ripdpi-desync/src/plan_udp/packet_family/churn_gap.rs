use ripdpi_config::DesyncGroup;
use ripdpi_packets::{packetize_quic_initial, QuicInitialBrowserProfile, QuicInitialPacketLayout};

use super::super::quic::{browser_like_quic_seed, packetize_browser_like_quic_initial, NormalizedQuicPlannerInput};

pub(super) fn build_quic_cid_churn_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut seed =
                browser_like_quic_seed(group, Some(normalized_quic), QuicInitialBrowserProfile::ChromeAndroid)?;
            if let Some(last) = seed.dcid.last_mut() {
                *last ^= (idx as u8).wrapping_add(normalized_quic.version as u8).max(1);
            }
            packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200)))
        })
        .collect()
}

pub(super) fn build_quic_packet_number_gap_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    if normalized_quic.is_none() {
        return Vec::new();
    }

    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout =
                QuicInitialPacketLayout::contiguous(normalized_quic.map_or(1200, |quic| quic.datagram_len.max(1200)));
            layout.packet_number = ((idx as u32) + 1) * 2;
            packetize_browser_like_quic_initial(
                group,
                normalized_quic,
                QuicInitialBrowserProfile::ChromeAndroid,
                layout,
            )
        })
        .collect()
}
