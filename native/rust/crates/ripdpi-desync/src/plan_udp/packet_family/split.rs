use ripdpi_config::{DesyncGroup, MAX_DUMMY_PREPEND_COUNT};
use ripdpi_packets::QuicInitialPacketLayout;

use super::super::quic::{
    NormalizedQuicPlannerInput, QUIC_INITIAL_MIN_PREFIX, effective_quic_realistic_host,
    packetize_browser_like_quic_initial, packetize_input_quic_initial, quic_browser_profile_for_index,
};

// Bounds allocation and output independently of the path MTU.
const MAX_DUMMY_PREPEND_DATAGRAM_BYTES: usize = 4096;

pub(super) fn build_dummy_prepend_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    if effective_quic_realistic_host(group).len() > MAX_DUMMY_PREPEND_DATAGRAM_BYTES {
        return Vec::new();
    }

    (0..count.clamp(0, MAX_DUMMY_PREPEND_COUNT) as usize)
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
        .filter(|packet| packet.len() <= MAX_DUMMY_PREPEND_DATAGRAM_BYTES)
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
    // A prelude must fit the datagram budget already used by the original.
    if packet.len() > normalized_quic.datagram_len.min(65_507) {
        return Vec::new();
    }
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

#[cfg(test)]
mod tests {
    use super::super::super::quic::normalized_quic_plan_input;
    use super::*;
    use ripdpi_packets::{
        QUIC_V1_VERSION, QUIC_V2_VERSION, build_realistic_quic_initial, packetize_quic_initial, parse_quic_initial_seed,
    };

    #[test]
    fn dpi18_sni_split_does_not_grow_an_unpadded_initial() {
        for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
            let packet = build_realistic_quic_initial(version, Some("example.test")).expect("Initial");
            let mut seed = parse_quic_initial_seed(&packet).expect("seed");
            seed.token = vec![0x42; 1200];
            let input =
                packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("unpadded Initial");
            let normalized = normalized_quic_plan_input(&input).expect("complete valid input");
            assert!(
                build_quic_sni_split_packets(Some(&normalized), 1).is_empty(),
                "extra CRYPTO framing must not grow the input"
            );
        }
    }

    #[test]
    fn dpi18_sni_split_keeps_the_size_of_a_padded_initial() {
        for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
            let input = build_realistic_quic_initial(version, Some("example.test")).expect("Initial");
            let normalized = normalized_quic_plan_input(&input).expect("complete input");
            let packets = build_quic_sni_split_packets(Some(&normalized), 1);
            assert_eq!(packets.len(), 1);
            assert_eq!(packets[0].len(), input.len());
        }
    }
}
