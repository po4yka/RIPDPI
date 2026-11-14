use rand::{TryCryptoRng, rngs::SysRng};
use ripdpi_config::DesyncGroup;
use ripdpi_packets::{QuicInitialBrowserProfile, QuicInitialPacketLayout, QuicInitialSeed, packetize_quic_initial};

use super::super::quic::{NormalizedQuicPlannerInput, browser_like_quic_seed, browser_like_quic_seed_for};

pub(super) fn build_quic_cid_churn_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
    first_ordinal: u64,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };

    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut seed =
                browser_like_quic_seed(group, Some(normalized_quic), QuicInitialBrowserProfile::ChromeAndroid)?;
            let mask = (first_ordinal + idx as u64 + u64::from(normalized_quic.version as u8) + 1).to_be_bytes();
            for (byte, mask_byte) in seed.dcid.iter_mut().rev().zip(mask.into_iter().rev()) {
                *byte ^= mask_byte;
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
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    let Some(seed) = fresh_quic_gap_seed(group, normalized_quic.version, &mut SysRng) else {
        return Vec::new();
    };

    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout = QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200));
            layout.packet_number = ((idx as u32) + 1) * 2;
            packetize_quic_initial(&seed, &layout)
        })
        .collect()
}

fn fresh_quic_gap_seed(group: &DesyncGroup, version: u32, rng: &mut impl TryCryptoRng) -> Option<QuicInitialSeed> {
    // Packet numbers restart in each series, so its Initial keys must also change.
    let mut entropy = [0u8; 32];
    rng.try_fill_bytes(&mut entropy).ok()?;
    let mut seed =
        browser_like_quic_seed_for(group, version, &entropy[16..], QuicInitialBrowserProfile::ChromeAndroid)?;
    seed.dcid = entropy[..16].to_vec();
    Some(seed)
}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use ripdpi_packets::{QUIC_V1_VERSION, QUIC_V2_VERSION, build_realistic_quic_initial, parse_quic_initial};

    use super::*;
    use crate::plan_udp::quic::normalized_quic_plan_input;

    fn assert_distinct_cids(version: u32) {
        let input = build_realistic_quic_initial(version, Some("real.example.test")).expect("input Initial");
        let normalized = normalized_quic_plan_input(&input).expect("normalized Initial");
        let packets = build_quic_cid_churn_packets(&DesyncGroup::new(0), Some(&normalized), 260, 0);
        assert_eq!(packets.len(), 260);
        let ids = packets.iter().map(|packet| packet[6..6 + usize::from(packet[5])].to_vec()).collect::<HashSet<_>>();
        assert_eq!(ids.len(), packets.len(), "every decoy must have its own DCID");
        for packet in packets {
            let parsed = parse_quic_initial(&packet).expect("Initial protection follows the emitted DCID");
            assert_eq!(parsed.version, version);
            assert_eq!(parsed.host(), b"www.wikipedia.org");
        }
    }

    #[test]
    fn quic_cid_churn_v1_does_not_repeat_at_byte_wrap() {
        assert_distinct_cids(QUIC_V1_VERSION);
    }

    #[test]
    fn quic_cid_churn_v2_does_not_repeat_at_zero_mask() {
        assert_distinct_cids(QUIC_V2_VERSION);
    }

    struct PartialEntropyFailure;

    impl rand::TryRng for PartialEntropyFailure {
        type Error = std::io::Error;

        fn try_next_u32(&mut self) -> Result<u32, Self::Error> {
            Err(std::io::Error::other("entropy unavailable"))
        }

        fn try_next_u64(&mut self) -> Result<u64, Self::Error> {
            Err(std::io::Error::other("entropy unavailable"))
        }

        fn try_fill_bytes(&mut self, output: &mut [u8]) -> Result<(), Self::Error> {
            output.fill(0xab);
            Err(std::io::Error::other("entropy unavailable"))
        }
    }

    impl rand::TryCryptoRng for PartialEntropyFailure {}

    #[test]
    fn no_gap_seed_after_entropy_failure() {
        assert!(fresh_quic_gap_seed(&DesyncGroup::new(0), QUIC_V1_VERSION, &mut PartialEntropyFailure).is_none());
    }
}
