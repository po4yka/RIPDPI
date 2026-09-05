use aes::Aes128;
use aes::cipher::{BlockCipherEncrypt, KeyInit as BlockKeyInit, array::Array};
use ring::aead::{self, AES_128_GCM, Aad, LessSafeKey, UnboundKey};

use crate::tls::tls_client_hello_marker_info_in_handshake;
use crate::types::{QUIC_V1_VERSION, QUIC_V2_VERSION, QuicInitialInfo, QuicInitialLayout};
use crate::util::read_u32;

use super::crypto::{quic_derive_client_initial_secret, quic_expand_label};
use super::frames::{collect_quic_crypto_frames, defrag_quic_crypto_frames, read_quic_varint};
use super::{QUIC_HP_SAMPLE_LEN, QUIC_INITIAL_MIN_LEN, QUIC_MAX_CID_LEN, QUIC_TAG_LEN};

#[derive(Debug, Clone, Copy)]
pub(super) struct QuicInitialHeader<'a> {
    pub(super) version: u32,
    pub(super) dcid: &'a [u8],
    pub(super) scid: &'a [u8],
    pub(super) token: &'a [u8],
    pub(super) payload_len: usize,
    pub(super) pn_offset: usize,
}

fn is_quic_v2(version: u32) -> bool {
    version == QUIC_V2_VERSION
}

pub(super) fn supported_quic_version(version: u32) -> bool {
    matches!(version, QUIC_V1_VERSION | QUIC_V2_VERSION)
}

pub(super) fn parse_quic_initial_header(buffer: &[u8]) -> Option<QuicInitialHeader<'_>> {
    if buffer.len() < QUIC_INITIAL_MIN_LEN {
        return None;
    }
    let header = parse_quic_initial_response_header(buffer)?;
    if header.dcid.is_empty() {
        return None;
    }
    Some(header)
}

pub(super) fn parse_quic_initial_response_header(buffer: &[u8]) -> Option<QuicInitialHeader<'_>> {
    if buffer.len() < 7 || (buffer[0] & 0xc0) != 0xc0 {
        return None;
    }
    let version = read_u32(buffer, 1)?;
    if !supported_quic_version(version) {
        return None;
    }
    let expected_prefix = if is_quic_v2(version) { 0xd0 } else { 0xc0 };
    if (buffer[0] & 0xf0) != expected_prefix {
        return None;
    }

    let dcid_len = *buffer.get(5)? as usize;
    if dcid_len > QUIC_MAX_CID_LEN {
        return None;
    }
    let dcid = buffer.get(6..6 + dcid_len)?;

    let mut offset = 6 + dcid_len;
    let scid_len = *buffer.get(offset)? as usize;
    if scid_len > QUIC_MAX_CID_LEN {
        return None;
    }
    offset += 1;
    let scid = buffer.get(offset..offset + scid_len)?;
    offset += scid_len;

    let (token_len, token_varint_len) = read_quic_varint(buffer, offset)?;
    offset += token_varint_len;
    let token_len: usize = token_len.try_into().ok()?;
    let token_end = offset.checked_add(token_len)?;
    let token = buffer.get(offset..token_end)?;
    offset = token_end;

    let (payload_len, payload_varint_len) = read_quic_varint(buffer, offset)?;
    offset += payload_varint_len;
    let payload_len: usize = payload_len.try_into().ok()?;
    buffer.get(offset..offset.checked_add(payload_len)?)?;

    Some(QuicInitialHeader { version, dcid, scid, token, payload_len, pn_offset: offset })
}

pub(super) fn decrypt_quic_initial_payload(buffer: &[u8], header: QuicInitialHeader<'_>) -> Option<Vec<u8>> {
    decrypt_quic_initial_payload_with_offset(buffer, header).map(|(payload, _)| payload)
}

fn decrypt_quic_initial_payload_with_offset(buffer: &[u8], header: QuicInitialHeader<'_>) -> Option<(Vec<u8>, usize)> {
    let secret = quic_derive_client_initial_secret(header.dcid, header.version)?;
    decrypt_quic_initial_with_secret(buffer, header, &secret)
}

pub(super) fn decrypt_quic_initial_with_secret(
    buffer: &[u8],
    header: QuicInitialHeader<'_>,
    secret: &[u8],
) -> Option<(Vec<u8>, usize)> {
    let buffer = buffer.get(..header.pn_offset.checked_add(header.payload_len)?)?;
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    let (key_label, iv_label, hp_label) = if is_quic_v2(header.version) {
        ("tls13 quicv2 key", "tls13 quicv2 iv", "tls13 quicv2 hp")
    } else {
        ("tls13 quic key", "tls13 quic iv", "tls13 quic hp")
    };
    quic_expand_label(secret, key_label, &mut key)?;
    quic_expand_label(secret, iv_label, &mut iv)?;
    quic_expand_label(secret, hp_label, &mut hp)?;

    let sample = buffer.get(header.pn_offset + 4..header.pn_offset + 4 + QUIC_HP_SAMPLE_LEN)?;
    let hp_cipher = Aes128::new_from_slice(&hp).ok()?;
    let mut sample_block = Array::try_from(sample).ok()?;
    hp_cipher.encrypt_block(&mut sample_block);

    let unprotected_first = buffer[0] ^ (sample_block[0] & 0x0f);
    if unprotected_first & 0x0c != 0 {
        return None;
    }
    let pn_len = ((unprotected_first & 0x03) + 1) as usize;
    let protected_pn = buffer.get(header.pn_offset..header.pn_offset + pn_len)?;
    let mut packet_number_bytes = [0u8; 4];
    let mut unprotected_pn = [0u8; 4];
    for idx in 0..pn_len {
        let value = protected_pn[idx] ^ sample_block[1 + idx];
        packet_number_bytes[4 - pn_len + idx] = value;
        unprotected_pn[4 - pn_len + idx] = value;
    }
    let packet_number = u32::from_be_bytes(packet_number_bytes);

    let ciphertext_payload_offset = header.pn_offset.checked_add(pn_len)?;
    let ciphertext_len = header.payload_len.checked_sub(pn_len + QUIC_TAG_LEN)?;
    let ciphertext = buffer.get(ciphertext_payload_offset..ciphertext_payload_offset + ciphertext_len)?.to_vec();
    let tag = buffer.get(ciphertext_payload_offset + ciphertext_len..header.pn_offset + header.payload_len)?;

    let mut aad = buffer.get(..header.pn_offset + pn_len)?.to_vec();
    aad[0] = unprotected_first;
    aad[header.pn_offset..header.pn_offset + pn_len].copy_from_slice(&unprotected_pn[4 - pn_len..]);

    let mut nonce_bytes = iv;
    let packet_number = u64::from(packet_number).to_be_bytes();
    for (slot, byte) in nonce_bytes[4..].iter_mut().zip(packet_number) {
        *slot ^= byte;
    }

    let unbound = UnboundKey::new(&AES_128_GCM, &key).ok()?;
    let opening_key = LessSafeKey::new(unbound);
    let nonce = aead::Nonce::try_assume_unique_for_key(&nonce_bytes).ok()?;
    let mut in_out = ciphertext;
    in_out.extend_from_slice(tag);
    let plaintext = opening_key.open_in_place(nonce, Aad::from(&aad), &mut in_out).ok()?;
    Some((plaintext.to_vec(), ciphertext_payload_offset))
}

pub fn is_quic_initial(buffer: &[u8]) -> bool {
    parse_quic_initial_header(buffer).is_some()
}

pub fn parse_quic_initial(buffer: &[u8]) -> Option<QuicInitialInfo> {
    let header = parse_quic_initial_header(buffer)?;
    let payload = decrypt_quic_initial_payload(buffer, header)?;
    let (client_hello, is_crypto_complete) = defrag_quic_crypto_frames(&payload)?;
    if !is_crypto_complete {
        return None;
    }
    let tls_info = tls_client_hello_marker_info_in_handshake(&client_hello)?;
    Some(QuicInitialInfo { version: header.version, client_hello, tls_info, is_crypto_complete })
}

pub fn parse_quic_initial_layout(buffer: &[u8]) -> Option<QuicInitialLayout> {
    let header = parse_quic_initial_header(buffer)?;
    let (payload, ciphertext_payload_offset) = decrypt_quic_initial_payload_with_offset(buffer, header)?;
    let crypto_frames = collect_quic_crypto_frames(&payload)?;
    let (client_hello, is_crypto_complete) = defrag_quic_crypto_frames(&payload)?;
    if !is_crypto_complete {
        return None;
    }
    let tls_info = tls_client_hello_marker_info_in_handshake(&client_hello)?;
    let info = QuicInitialInfo { version: header.version, client_hello, tls_info, is_crypto_complete };
    Some(QuicInitialLayout { info, ciphertext_payload_offset, crypto_frames })
}
