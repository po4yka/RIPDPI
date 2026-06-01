use crate::normalize_quic_initial;

use ripdpi_config::DesyncGroup;
use ripdpi_packets::{
    QuicInitialBrowserProfile, QuicInitialPacketLayout, QuicInitialSeed, build_browser_like_quic_initial_seed,
    packetize_quic_initial, parse_quic_initial_seed,
};

pub(super) const QUIC_INITIAL_MIN_PREFIX: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct NormalizedQuicPlannerInput {
    pub(super) version: u32,
    pub(super) authority_split_offset: usize,
    pub(super) crypto_split_offset: usize,
    pub(super) client_hello_len: usize,
    pub(super) authority_host: String,
    pub(super) datagram_len: usize,
    seed: QuicInitialSeed,
}

pub(super) fn normalized_quic_plan_input(payload: &[u8]) -> Option<NormalizedQuicPlannerInput> {
    let ir = normalize_quic_initial(payload)?;
    let seed = parse_quic_initial_seed(payload)?;
    let client_hello_len = ir.tls_client_hello.raw.len();
    let crypto_split_offset = ir
        .desired
        .crypto_frame_boundaries
        .first()
        .copied()
        .filter(|offset| *offset > 0 && *offset < client_hello_len)
        .unwrap_or_else(|| (client_hello_len / 2).max(1));

    Some(NormalizedQuicPlannerInput {
        version: ir.version,
        authority_split_offset: ir.tls_client_hello.authority_span.start,
        crypto_split_offset,
        client_hello_len,
        authority_host: String::from_utf8(ir.tls_client_hello.authority).ok()?,
        datagram_len: payload.len(),
        seed,
    })
}

pub(super) fn effective_quic_realistic_host<'a>(
    group: &'a DesyncGroup,
    normalized_quic: Option<&'a NormalizedQuicPlannerInput>,
) -> Option<&'a str> {
    group.actions.quic_fake_host.as_deref().or(normalized_quic.map(|quic| quic.authority_host.as_str()))
}

pub(super) fn browser_like_quic_seed(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    profile: QuicInitialBrowserProfile,
) -> Option<QuicInitialSeed> {
    let normalized_quic = normalized_quic?;
    build_browser_like_quic_initial_seed(
        normalized_quic.version,
        effective_quic_realistic_host(group, Some(normalized_quic)),
        profile,
    )
}

pub(super) fn packetize_input_quic_initial(
    normalized_quic: &NormalizedQuicPlannerInput,
    layout: QuicInitialPacketLayout,
) -> Option<Vec<u8>> {
    packetize_quic_initial(&normalized_quic.seed, &layout)
}

pub(super) fn packetize_browser_like_quic_initial(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    profile: QuicInitialBrowserProfile,
    layout: QuicInitialPacketLayout,
) -> Option<Vec<u8>> {
    let seed = browser_like_quic_seed(group, normalized_quic, profile)?;
    packetize_quic_initial(&seed, &layout)
}

pub(super) fn quic_browser_profile_for_index(idx: usize) -> QuicInitialBrowserProfile {
    if idx.is_multiple_of(2) {
        QuicInitialBrowserProfile::ChromeAndroid
    } else {
        QuicInitialBrowserProfile::FirefoxAndroid
    }
}
