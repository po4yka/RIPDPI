use aes::cipher::{array::Array, BlockCipherEncrypt, KeyInit as BlockKeyInit};
use aes::Aes128;
use ring::aead::{self, Aad, LessSafeKey, UnboundKey, AES_128_GCM};

use crate::tls::{is_tls_client_hello, tls_client_hello_marker_info_in_handshake, TLS_RECORD_HEADER_LEN};
use crate::types::{
    QuicInitialInfo, QuicInitialLayout, DEFAULT_FAKE_QUIC_COMPAT_LEN, QUIC_V1_VERSION, QUIC_V2_VERSION,
};
use crate::util::read_u32;

use super::crypto::{quic_derive_client_initial_secret, quic_expand_label};
use super::frames::{
    append_quic_crypto_frame, collect_quic_crypto_frames, defrag_quic_crypto_frames, encode_quic_varint,
    read_quic_varint,
};
use super::{
    QUIC_FAKE_DCID, QUIC_FAKE_INITIAL_TARGET_LEN, QUIC_FAKE_SCID, QUIC_HP_SAMPLE_LEN, QUIC_INITIAL_MIN_LEN,
    QUIC_MAX_CID_LEN, QUIC_TAG_LEN,
};

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

    let packet_number = packet_number.to_be_bytes();
    let mut aad = header.clone();
    aad.extend_from_slice(&packet_number);

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
    let nonce = aead::Nonce::try_assume_unique_for_key(&iv).ok()?;
    let mut ciphertext = plaintext;
    let tag = sealing_key.seal_in_place_separate_tag(nonce, Aad::from(&aad), &mut ciphertext).ok()?;

    let hp_cipher = Aes128::new_from_slice(&hp).ok()?;
    let mut sample = Array::try_from(ciphertext.get(..QUIC_HP_SAMPLE_LEN)?).ok()?;
    hp_cipher.encrypt_block(&mut sample);

    let mut packet = header;
    packet.extend((0..4).map(|idx| packet_number[idx] ^ sample[1 + idx]));
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

pub(super) fn parse_quic_initial_header(buffer: &[u8]) -> Option<QuicInitialHeader<'_>> {
    if buffer.len() < QUIC_INITIAL_MIN_LEN || (buffer[0] & 0x80) == 0 || (buffer[0] & 0x40) == 0 {
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
    if dcid_len == 0 || dcid_len > QUIC_MAX_CID_LEN {
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
    let token = buffer.get(offset..offset + token_len)?;
    offset += token_len;

    let (payload_len, payload_varint_len) = read_quic_varint(buffer, offset)?;
    offset += payload_varint_len;
    let payload_len: usize = payload_len.try_into().ok()?;
    buffer.get(offset..offset + payload_len)?;

    Some(QuicInitialHeader { version, dcid, scid, token, payload_len, pn_offset: offset })
}

pub(super) fn decrypt_quic_initial_payload(buffer: &[u8], header: QuicInitialHeader<'_>) -> Option<Vec<u8>> {
    let secret = quic_derive_client_initial_secret(header.dcid, header.version)?;
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    let (key_label, iv_label, hp_label) = if is_quic_v2(header.version) {
        ("tls13 quicv2 key", "tls13 quicv2 iv", "tls13 quicv2 hp")
    } else {
        ("tls13 quic key", "tls13 quic iv", "tls13 quic hp")
    };
    quic_expand_label(&secret, key_label, &mut key)?;
    quic_expand_label(&secret, iv_label, &mut iv)?;
    quic_expand_label(&secret, hp_label, &mut hp)?;

    let sample = buffer.get(header.pn_offset + 4..header.pn_offset + 4 + QUIC_HP_SAMPLE_LEN)?;
    let hp_cipher = Aes128::new_from_slice(&hp).ok()?;
    let mut sample_block = Array::try_from(sample).ok()?;
    hp_cipher.encrypt_block(&mut sample_block);

    let unprotected_first = buffer[0] ^ (sample_block[0] & 0x0f);
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

    let ciphertext_len = header.payload_len.checked_sub(pn_len + QUIC_TAG_LEN)?;
    let ciphertext = buffer.get(header.pn_offset + pn_len..header.pn_offset + pn_len + ciphertext_len)?.to_vec();
    let tag = buffer.get(header.pn_offset + pn_len + ciphertext_len..header.pn_offset + header.payload_len)?;

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
    Some(plaintext.to_vec())
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
    let payload = decrypt_quic_initial_payload(buffer, header)?;
    let crypto_frames = collect_quic_crypto_frames(&payload)?;
    let (client_hello, is_crypto_complete) = defrag_quic_crypto_frames(&payload)?;
    if !is_crypto_complete {
        return None;
    }
    let tls_info = tls_client_hello_marker_info_in_handshake(&client_hello)?;
    let info = QuicInitialInfo { version: header.version, client_hello, tls_info, is_crypto_complete };
    Some(QuicInitialLayout { info, crypto_frames })
}
