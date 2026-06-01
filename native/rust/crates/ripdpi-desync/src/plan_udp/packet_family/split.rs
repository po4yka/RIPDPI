use ripdpi_config::DesyncGroup;
use ripdpi_packets::QuicInitialPacketLayout;

use super::super::quic::{
    NormalizedQuicPlannerInput, QUIC_INITIAL_MIN_PREFIX, packetize_browser_like_quic_initial,
    packetize_input_quic_initial, quic_browser_profile_for_index,
};

pub(super) fn build_dummy_prepend_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout = QuicInitialPacketLayout::contiguous(QUIC_INITIAL_MIN_PREFIX + (idx * 32));
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

pub(super) fn build_quic_sni_split_packets(
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    let layout =
        QuicInitialPacketLayout::split_at(normalized_quic.authority_split_offset, normalized_quic.datagram_len);
    let Some(packet) = packetize_input_quic_initial(normalized_quic, layout) else {
        return Vec::new();
    };
    vec![packet; count as usize]
}

pub(super) fn build_quic_crypto_split_packets(
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    let split_at = normalized_quic.crypto_split_offset.min(normalized_quic.client_hello_len.saturating_sub(1));
    let layout = QuicInitialPacketLayout::split_at(split_at, normalized_quic.datagram_len);
    let Some(packet) = packetize_input_quic_initial(normalized_quic, layout) else {
        return Vec::new();
    };
    vec![packet; count.max(1) as usize]
}
