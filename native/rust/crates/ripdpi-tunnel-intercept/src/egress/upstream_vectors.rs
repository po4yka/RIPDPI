//! Fixed upstream packet examples shared by packet and strategy tests.

include!("packet_vectors.rs");

pub(super) fn decode(hex: &str) -> Vec<u8> {
    hex.split_ascii_whitespace().map(|byte| u8::from_str_radix(byte, 16).expect("upstream hex byte")).collect()
}
