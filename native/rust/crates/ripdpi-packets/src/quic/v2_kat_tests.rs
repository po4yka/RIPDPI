//! RFC 9369 Appendix A known-answer vectors for QUIC v2 Initial protection.
//! Every expected byte below is transcribed from the RFC, not from `crypto.rs` or the builder.

use aes::Aes128;
use aes::cipher::{BlockCipherEncrypt, KeyInit, array::Array};
use ring::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};

use super::build_realistic_quic_initial;
use super::crypto::{quic_derive_initial_secret, quic_expand_label};
use crate::types::QUIC_V2_VERSION;

fn hex(text: &str) -> Vec<u8> {
    assert!(text.len().is_multiple_of(2));
    (0..text.len()).step_by(2).map(|at| u8::from_str_radix(&text[at..at + 2], 16).expect("hex digit")).collect()
}

// RFC 9369 Appendix A uses the RFC 9001 Appendix A connection ID. The client and server
// secrets are HKDF-Extract(initial_salt, DCID), so they also check the v2 salt.
const RFC_DCID: &str = "8394c8f03e515708";
const RFC_CLIENT_SECRET: &str = "14ec9d6eb9fd7af83bf5a668bc17a7e283766aade7ecd0891f70f9ff7f4bf47b";
const RFC_CLIENT_KEY: &str = "8b1a0bc121284290a29e0971b5cd045d";
const RFC_CLIENT_IV: &str = "91f73e2351d8fa91660e909f";
const RFC_CLIENT_HP: &str = "45b95e15235d6f45a6b19cbcb0294ba9";
const RFC_SERVER_SECRET: &str = "0263db1782731bf4588e7e4d93b7463907cb8cd8200b5da55a8bd488eafc37c1";
const RFC_SERVER_KEY: &str = "82db637861d55e1d011f19ea71d5d2a7";
const RFC_SERVER_IV: &str = "dd13c276499c0249d3310652";
const RFC_SERVER_HP: &str = "edf6d05c83121201b436e16877593c3a";

fn expand(secret: &[u8], label: &str, len: usize) -> Vec<u8> {
    let mut out = vec![0; len];
    quic_expand_label(secret, label, &mut out).expect("expand label");
    out
}

#[test]
fn v2_client_initial_keys_match_rfc_9369_appendix_a() {
    let secret = quic_derive_initial_secret(&hex(RFC_DCID), QUIC_V2_VERSION, "tls13 client in").expect("secret");
    assert_eq!(secret.as_slice(), hex(RFC_CLIENT_SECRET));
    assert_eq!(expand(&secret, "tls13 quicv2 key", 16), hex(RFC_CLIENT_KEY));
    assert_eq!(expand(&secret, "tls13 quicv2 iv", 12), hex(RFC_CLIENT_IV));
    assert_eq!(expand(&secret, "tls13 quicv2 hp", 16), hex(RFC_CLIENT_HP));
}

#[test]
fn v2_server_initial_keys_match_rfc_9369_appendix_a() {
    let secret = quic_derive_initial_secret(&hex(RFC_DCID), QUIC_V2_VERSION, "tls13 server in").expect("secret");
    assert_eq!(secret.as_slice(), hex(RFC_SERVER_SECRET));
    assert_eq!(expand(&secret, "tls13 quicv2 key", 16), hex(RFC_SERVER_KEY));
    assert_eq!(expand(&secret, "tls13 quicv2 iv", 12), hex(RFC_SERVER_IV));
    assert_eq!(expand(&secret, "tls13 quicv2 hp", 16), hex(RFC_SERVER_HP));
}

/// Open the builder's v2 Initial with the RFC client key, IV and HP key alone,
/// so the builder's own label choice and packet layout are under test.
#[test]
fn v2_builder_initial_opens_with_rfc_9369_client_keys() {
    let mut packet = build_realistic_quic_initial(QUIC_V2_VERSION, Some("v2.example.test")).expect("v2 Initial");

    // Long header, fixed bit, RFC 9369 section 3.2 Initial type 0b01.
    assert_eq!(packet[0] & 0xf0, 0xd0);
    assert_eq!(packet[1..5], [0x6b, 0x33, 0x43, 0xcf]);
    let dcid_len = usize::from(packet[5]);
    assert_eq!(packet[6..6 + dcid_len], hex(RFC_DCID));
    let mut at = 6 + dcid_len;
    at += 1 + usize::from(packet[at]); // SCID
    assert_eq!(packet[at], 0, "empty token");
    at += 1;
    let length_len = 1 << (packet[at] >> 6);
    let mut length = u64::from(packet[at] & 0x3f);
    for byte in &packet[at + 1..at + length_len] {
        length = length << 8 | u64::from(*byte);
    }
    let pn_offset = at + length_len;
    assert_eq!(pn_offset + usize::try_from(length).expect("length"), packet.len());

    let hp = Aes128::new_from_slice(&hex(RFC_CLIENT_HP)).expect("hp key");
    let mut mask = Array::try_from(&packet[pn_offset + 4..pn_offset + 20]).expect("sample");
    hp.encrypt_block(&mut mask);
    packet[0] ^= mask[0] & 0x0f;
    let pn_len = usize::from(packet[0] & 0x03) + 1;
    let mut nonce = <[u8; 12]>::try_from(hex(RFC_CLIENT_IV)).expect("iv");
    for index in 0..pn_len {
        packet[pn_offset + index] ^= mask[1 + index];
        nonce[12 - pn_len + index] ^= packet[pn_offset + index];
    }

    let (header, body) = packet.split_at_mut(pn_offset + pn_len);
    let key = LessSafeKey::new(UnboundKey::new(&AES_128_GCM, &hex(RFC_CLIENT_KEY)).expect("key"));
    let plaintext = key
        .open_in_place(Nonce::assume_unique_for_key(nonce), Aad::from(&*header), body)
        .expect("RFC 9369 client keys open the builder's v2 Initial");
    assert_eq!(plaintext[0], 0x06, "payload starts with a CRYPTO frame");
}
