use crate::normalize_quic_initial;

use ripdpi_config::DesyncGroup;
use ripdpi_packets::{
    QUIC_V1_VERSION, QUIC_V2_VERSION, QuicInitialBrowserProfile, QuicInitialPacketLayout, QuicInitialSeed,
    build_browser_like_quic_initial_seed, is_quic_initial, is_tls_client_hello, packetize_quic_initial,
    parse_quic_initial_seed,
};

/// Version of a QUIC long-header Initial datagram. Unlike
/// `normalized_quic_plan_input`, this does not need a complete ClientHello.
pub(super) fn quic_initial_header_version(payload: &[u8]) -> Option<u32> {
    if !is_quic_initial(payload) {
        return None;
    }
    Some(u32::from_be_bytes(payload.get(1..5)?.try_into().ok()?))
}

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

/// SNI of realistic QUIC decoys without a `quic_fake_host` override. It
/// matches the app's `DefaultQuicFakeHost`: a decoy must never carry the real
/// (possibly blocked) authority, or an SNI-matching DPI that sees the decoy
/// blocks the flow before the real Initial.
const DEFAULT_QUIC_FAKE_HOST: &str = "www.wikipedia.org";

pub(super) fn effective_quic_realistic_host(group: &DesyncGroup) -> &str {
    group.actions.quic_fake_host.as_deref().unwrap_or(DEFAULT_QUIC_FAKE_HOST)
}

/// What realistic decoys take from the input Initial: its header fields and
/// datagram length. They need no ClientHello, so a ClientHello that spans
/// several datagrams does not stop them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct QuicDecoyBasis {
    pub(super) version: u32,
    pub(super) datagram_len: usize,
    dcid: Vec<u8>,
}

impl QuicDecoyBasis {
    pub(super) fn from_normalized(normalized_quic: &NormalizedQuicPlannerInput) -> Self {
        Self {
            version: normalized_quic.version,
            datagram_len: normalized_quic.datagram_len,
            dcid: normalized_quic.seed.dcid.clone(),
        }
    }

    /// Basis of a long-header Initial of a supported version whose header holds
    /// the whole DCID. Anything else gives `None`.
    pub(super) fn from_payload(payload: &[u8]) -> Option<Self> {
        let version = quic_initial_header_version(payload)?;
        if !matches!(version, QUIC_V1_VERSION | QUIC_V2_VERSION) {
            return None;
        }
        let dcid_len = usize::from(*payload.get(5)?);
        let dcid = payload.get(6..6 + dcid_len)?.to_vec();
        Some(Self { version, datagram_len: payload.len(), dcid })
    }

    /// Basis from the normalized input when there is one, else from the header.
    pub(super) fn of_input(normalized_quic: Option<&NormalizedQuicPlannerInput>, payload: &[u8]) -> Option<Self> {
        normalized_quic.map(Self::from_normalized).or_else(|| Self::from_payload(payload))
    }
}

pub(super) fn packetize_decoy_for_basis(
    group: &DesyncGroup,
    basis: &QuicDecoyBasis,
    profile: QuicInitialBrowserProfile,
    layout: QuicInitialPacketLayout,
) -> Option<Vec<u8>> {
    packetize_quic_initial(&browser_like_quic_seed_for(group, basis.version, &basis.dcid, profile)?, &layout)
}

/// Deterministic bytes for one decoy field: splitmix64 over an FNV-1a hash of
/// the input DCID and a field label. Decoys of one flow agree and decoys of
/// different flows differ, with no fixed value shared by every user.
fn derived_bytes<const N: usize>(dcid: &[u8], label: u8) -> [u8; N] {
    let mut state = dcid
        .iter()
        .chain([&label])
        .fold(0xcbf2_9ce4_8422_2325_u64, |hash, byte| (hash ^ u64::from(*byte)).wrapping_mul(0x0100_0000_01b3));
    let mut out = [0_u8; N];
    for chunk in out.chunks_mut(8) {
        state = state.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut mixed = state;
        mixed = (mixed ^ (mixed >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        mixed = (mixed ^ (mixed >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        mixed ^= mixed >> 31;
        chunk.copy_from_slice(&mixed.to_be_bytes()[..chunk.len()]);
    }
    out
}

/// Offset of the ClientHello random in a TLS record: record header (5),
/// handshake type and length (4), legacy version (2).
const CLIENT_HELLO_RANDOM_OFFSET: usize = 5 + 4 + 2;
const CLIENT_HELLO_RANDOM_LEN: usize = 32;

/// Decoy seed for a flow whose input Initial has version `version` and
/// destination connection ID `input_dcid`. The decoy keeps the seed's source
/// connection ID: the ClientHello's `initial_source_connection_id` transport
/// parameter must match it.
pub(super) fn browser_like_quic_seed_for(
    group: &DesyncGroup,
    version: u32,
    input_dcid: &[u8],
    profile: QuicInitialBrowserProfile,
) -> Option<QuicInitialSeed> {
    let mut seed = build_browser_like_quic_initial_seed(version, Some(effective_quic_realistic_host(group)), profile)?;
    let mut dcid = derived_bytes::<8>(input_dcid, 1);
    if dcid.as_slice() == input_dcid {
        dcid[0] ^= 0xff;
    }
    seed.dcid = dcid.to_vec();

    let random_end = CLIENT_HELLO_RANDOM_OFFSET + CLIENT_HELLO_RANDOM_LEN;
    let mut client_hello = seed.client_hello.clone();
    if let Some(random) = client_hello.get_mut(CLIENT_HELLO_RANDOM_OFFSET..random_end) {
        random.copy_from_slice(&derived_bytes::<CLIENT_HELLO_RANDOM_LEN>(input_dcid, 2));
        if is_tls_client_hello(&client_hello) {
            seed.client_hello = client_hello;
        }
    }
    Some(seed)
}

pub(super) fn browser_like_quic_seed(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    profile: QuicInitialBrowserProfile,
) -> Option<QuicInitialSeed> {
    let normalized_quic = normalized_quic?;
    browser_like_quic_seed_for(group, normalized_quic.version, &normalized_quic.seed.dcid, profile)
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
