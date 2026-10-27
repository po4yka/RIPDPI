use ripdpi_config::DesyncGroup;
use ripdpi_packets::{QuicInitialBrowserProfile, QuicInitialPacketLayout};

use super::super::quic::{
    NormalizedQuicPlannerInput, packetize_browser_like_quic_initial, quic_browser_profile_for_index,
};

/// Smallest client Initial datagram (RFC 9000, section 14.1).
const QUIC_MIN_INITIAL_DATAGRAM: usize = 1200;
/// Largest decoy that a 1500-byte path carries unfragmented over IPv4
/// (1472) and IPv6 (1452). The planner does not know the path MTU.
const QUIC_MAX_DECOY_DATAGRAM: usize = 1452;

/// Minimum datagram length of each decoy, one per rung offset: the input
/// length (at least 1200) plus the rung. No decoy exceeds the ceiling, which
/// is the input length or, when the ladder needs more room,
/// [`QUIC_MAX_DECOY_DATAGRAM`]. A ladder that would pass the ceiling moves
/// down and keeps its spacing while it stays at or above 1200.
fn decoy_datagram_lengths(input_len: Option<usize>, rungs: &[usize]) -> Vec<usize> {
    let base = input_len.unwrap_or(0).max(QUIC_MIN_INITIAL_DATAGRAM);
    let top = rungs.iter().copied().max().unwrap_or(0);
    // The input datagram already crossed the path, so it never lowers the ceiling.
    let ceiling = base.max((QUIC_MIN_INITIAL_DATAGRAM + top).min(QUIC_MAX_DECOY_DATAGRAM));
    let start = base.min(ceiling.saturating_sub(top)).max(QUIC_MIN_INITIAL_DATAGRAM);
    rungs.iter().map(|rung| (start + rung).min(ceiling)).collect()
}

pub(super) fn build_quic_padding_ladder_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let rungs = (0..count.max(1) as usize).map(|idx| 8 * (idx + 1)).collect::<Vec<_>>();
    let lengths = decoy_datagram_lengths(normalized_quic.map(|quic| quic.datagram_len), &rungs);
    rungs
        .into_iter()
        .zip(lengths)
        .filter_map(|(rung, len)| {
            // The rung goes into the minimum length too: the builder pads up to
            // that minimum, which would otherwise absorb the tail padding.
            let mut layout = QuicInitialPacketLayout::contiguous(len);
            layout.extra_tail_padding = rung;
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

    let rungs = (0..count.max(2) as usize).map(|idx| idx * 8).collect::<Vec<_>>();
    let lengths = decoy_datagram_lengths(Some(normalized_quic.datagram_len), &rungs);
    rungs
        .into_iter()
        .zip(lengths)
        .enumerate()
        .filter_map(|(idx, (rung, len))| {
            let mut layout = QuicInitialPacketLayout::contiguous(len);
            layout.extra_tail_padding = rung;
            packetize_browser_like_quic_initial(
                group,
                Some(normalized_quic),
                quic_browser_profile_for_index(idx),
                layout,
            )
        })
        .collect()
}
