use aes::Aes128;
use aes::cipher::{BlockCipherEncrypt, KeyInit as BlockKeyInit, array::Array};
use ring::aead::{self, AES_128_GCM, Aad, LessSafeKey, UnboundKey};

use crate::tls::{TLS_RECORD_HEADER_LEN, is_tls_client_hello};
use crate::types::{DEFAULT_FAKE_QUIC_COMPAT_LEN, QUIC_V1_VERSION, QUIC_V2_VERSION};

use super::crypto::{quic_derive_client_initial_secret, quic_expand_label};
use super::frames::{append_quic_crypto_frame, encode_quic_varint};
use super::parse::supported_quic_version;
use super::{QUIC_FAKE_DCID, QUIC_FAKE_INITIAL_TARGET_LEN, QUIC_FAKE_SCID, QUIC_HP_SAMPLE_LEN, QUIC_TAG_LEN};

pub fn default_fake_quic_compat() -> Vec<u8> {
    let mut packet = vec![0; DEFAULT_FAKE_QUIC_COMPAT_LEN];
    packet[0] = 0x40;
    packet
}

/// Build and encrypt a QUIC Initial packet from raw parts.
///
/// `dcid` and `scid` are the connection IDs to place in the header.
/// `token` is the retry/initial token (empty slice for none).
/// `plaintext` is the already-assembled payload (CRYPTO frames + padding).
/// Keys are derived from `dcid`.
pub(super) fn build_quic_initial_raw(
    version: u32,
    dcid: &[u8],
    scid: &[u8],
    token: &[u8],
    mut plaintext: Vec<u8>,
    min_total_len: usize,
    packet_number: u32,
) -> Option<Vec<u8>> {
    let token_varint = encode_quic_varint(token.len() as u64);

    // Pad plaintext so the total packet reaches min_total_len.
    loop {
        let payload_len = 4 + plaintext.len() + QUIC_TAG_LEN;
        let payload_len_varint = encode_quic_varint(payload_len as u64);
        let header_len = 1 + 4 + 1 + dcid.len() + 1 + scid.len() + token_varint.len() + payload_len_varint.len();
        let total_len = header_len + payload_len;
        if total_len >= min_total_len {
            break;
        }
        plaintext.extend(std::iter::repeat_n(0u8, min_total_len - total_len));
    }

    let payload_len = 4 + plaintext.len() + QUIC_TAG_LEN;
    let payload_len_varint = encode_quic_varint(payload_len as u64);
    let first_byte = if version == QUIC_V2_VERSION { 0xd3 } else { 0xc3 };

    let mut header = Vec::new();
    header.push(first_byte);
    header.extend_from_slice(&version.to_be_bytes());
    header.push(dcid.len() as u8);
    header.extend_from_slice(dcid);
    header.push(scid.len() as u8);
    header.extend_from_slice(scid);
    header.extend_from_slice(&token_varint);
    header.extend_from_slice(&payload_len_varint);

    let packet_number_bytes = packet_number.to_be_bytes();
    let mut aad = header.clone();
    aad.extend_from_slice(&packet_number_bytes);

    let secret = quic_derive_client_initial_secret(dcid, version)?;
    let (key_label, iv_label, hp_label) = if version == QUIC_V2_VERSION {
        ("tls13 quicv2 key", "tls13 quicv2 iv", "tls13 quicv2 hp")
    } else {
        ("tls13 quic key", "tls13 quic iv", "tls13 quic hp")
    };
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    quic_expand_label(&secret, key_label, &mut key)?;
    quic_expand_label(&secret, iv_label, &mut iv)?;
    quic_expand_label(&secret, hp_label, &mut hp)?;

    let unbound = UnboundKey::new(&AES_128_GCM, &key).ok()?;
    let sealing_key = LessSafeKey::new(unbound);
    // Nonce = IV XOR packet number (RFC 9001 section 5.3), right-aligned to
    // the 12-byte IV. Must mirror the decrypt path in parse.rs: sealing with
    // a raw IV would reuse one nonce for every packet number under keys that
    // only vary with DCID, breaking AEAD confidentiality and server decoding.
    let mut nonce_bytes = iv;
    for (slot, byte) in nonce_bytes[4..].iter_mut().zip(u64::from(packet_number).to_be_bytes()) {
        *slot ^= byte;
    }
    let nonce = aead::Nonce::try_assume_unique_for_key(&nonce_bytes).ok()?;
    let mut ciphertext = plaintext;
    let tag = sealing_key.seal_in_place_separate_tag(nonce, Aad::from(&aad), &mut ciphertext).ok()?;

    let hp_cipher = Aes128::new_from_slice(&hp).ok()?;
    let mut sample = Array::try_from(ciphertext.get(..QUIC_HP_SAMPLE_LEN)?).ok()?;
    hp_cipher.encrypt_block(&mut sample);

    let mut packet = header;
    packet.extend((0..4).map(|idx| packet_number_bytes[idx] ^ sample[1 + idx]));
    packet.extend_from_slice(&ciphertext);
    packet.extend_from_slice(tag.as_ref());
    packet[0] ^= sample[0] & 0x0f;
    Some(packet)
}

pub fn build_quic_initial_from_tls(version: u32, tls_client_hello: &[u8], gap_after_split: usize) -> Option<Vec<u8>> {
    let version = if supported_quic_version(version) { version } else { QUIC_V1_VERSION };
    if !is_tls_client_hello(tls_client_hello) || tls_client_hello.len() <= TLS_RECORD_HEADER_LEN {
        return None;
    }

    let crypto = tls_client_hello.get(TLS_RECORD_HEADER_LEN..)?.to_vec();
    let split = crypto.len() / 2;
    let mut plaintext = Vec::new();
    append_quic_crypto_frame(&mut plaintext, 0, &crypto[..split]);
    append_quic_crypto_frame(&mut plaintext, (split + gap_after_split) as u64, &crypto[split..]);

    build_quic_initial_raw(version, &QUIC_FAKE_DCID, &QUIC_FAKE_SCID, &[], plaintext, QUIC_FAKE_INITIAL_TARGET_LEN, 0)
}
