use ripdpi_config::{DesyncGroup, MAX_QUIC_PADDING_LADDER_COUNT};
use ripdpi_packets::{QuicInitialBrowserProfile, QuicInitialPacketLayout};

use super::super::quic::{
    NormalizedQuicPlannerInput, QuicDecoyBasis, packetize_decoy_for_basis, packetize_synthetic_quic_initial,
    quic_browser_profile_for_index,
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

/// Each decoy of a flow takes the next packet number, so no two share an AEAD
/// nonce (RFC 9000, section 12.3).
fn numbered_layout(len: usize, idx: usize) -> QuicInitialPacketLayout {
    let mut layout = QuicInitialPacketLayout::contiguous(len);
    layout.packet_number = idx as u32;
    layout
}

pub(super) fn build_quic_padding_ladder_packets(
    group: &DesyncGroup,
    basis: Option<&QuicDecoyBasis>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(basis) = basis else {
        return Vec::new();
    };
    let count = count.clamp(1, MAX_QUIC_PADDING_LADDER_COUNT);
    let rungs = (0..count as usize).map(|idx| 8 * (idx + 1)).collect::<Vec<_>>();
    let lengths = decoy_datagram_lengths(Some(basis.datagram_len), &rungs);
    lengths
        .into_iter()
        .enumerate()
        .filter_map(|(idx, len)| {
            // The builder pads to `len`; a decoy whose natural size passed it
            // would break the ceiling, so it is dropped.
            packetize_decoy_for_basis(group, basis, QuicInitialBrowserProfile::ChromeAndroid, numbered_layout(len, idx))
                .filter(|packet| packet.len() == len)
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

    // A local input does not establish the remote PMTU. PADDING fills the
    // minimum QUIC budget; index-based growth must not expand that budget.
    (0..count.max(2) as usize)
        .filter_map(|idx| {
            let layout = QuicInitialPacketLayout::contiguous(QUIC_MIN_INITIAL_DATAGRAM);
            let packet =
                packetize_synthetic_quic_initial(group, normalized_quic, quic_browser_profile_for_index(idx), layout)?;
            (packet.len() <= QUIC_MIN_INITIAL_DATAGRAM).then_some(packet)
        })
        .collect()
}

#[cfg(test)]
#[path = "realistic_initial_tests.rs"]
mod tests;
