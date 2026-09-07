mod build;
mod crypto;
mod fake_initial;
mod frames;
mod parse;
mod response;
mod tamper;

pub use build::{build_quic_initial_from_tls, default_fake_quic_compat};
pub use fake_initial::{
    build_browser_like_quic_initial, build_browser_like_quic_initial_seed, build_generic_quic_initial,
    build_realistic_quic_initial, packetize_quic_initial, parse_quic_initial_seed,
};
pub use parse::{is_quic_initial, parse_quic_initial, parse_quic_initial_layout};
pub use response::{QuicResponseKind, build_probe_quic_initial, validate_quic_response};
pub use tamper::{tamper_quic_initial_split_crypto, tamper_quic_initial_split_sni, tamper_quic_version};

const QUIC_INITIAL_MIN_LEN: usize = 128;
const QUIC_HP_SAMPLE_LEN: usize = 16;
const QUIC_TAG_LEN: usize = 16;
const QUIC_MAX_CID_LEN: usize = 20;
const QUIC_MAX_CRYPTO_LEN: usize = 64 * 1024;
const QUIC_FAKE_INITIAL_TARGET_LEN: usize = 1200;
const QUIC_FAKE_DCID: [u8; 8] = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
const QUIC_FAKE_SCID: [u8; 4] = [0x11, 0x22, 0x33, 0x44];

#[cfg(test)]
use crate::tls::{TLS_RECORD_HEADER_LEN, is_tls_client_hello};
#[cfg(test)]
use crate::types::{
    DEFAULT_FAKE_QUIC_COMPAT_LEN, QUIC_V1_VERSION, QUIC_V2_VERSION, QuicInitialBrowserProfile, QuicInitialPacketLayout,
};
#[cfg(test)]
use crypto::{quic_derive_client_initial_secret, quic_expand_label, quic_hkdf_label};
#[cfg(test)]
use frames::{append_quic_crypto_frame, defrag_quic_crypto_frames, encode_quic_varint, read_quic_varint};
#[cfg(test)]
use parse::parse_quic_initial_header;

#[cfg(test)]
mod tests;
