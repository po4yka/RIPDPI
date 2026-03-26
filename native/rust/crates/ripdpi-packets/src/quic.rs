use aes::cipher::{generic_array::GenericArray, BlockEncrypt, KeyInit as BlockKeyInit};
use aes::Aes128;
use ring::aead::{self, Aad, LessSafeKey, UnboundKey, AES_128_GCM};
use ring::hkdf::{self, KeyType, Salt, HKDF_SHA256};

use crate::tls::{
    change_tls_sni_seeded_like_c, is_tls_client_hello, tls_client_hello_marker_info_in_handshake, TLS_RECORD_HEADER_LEN,
};
use crate::types::{QuicInitialInfo, DEFAULT_FAKE_QUIC_COMPAT_LEN, DEFAULT_FAKE_TLS, QUIC_V1_VERSION, QUIC_V2_VERSION};
use crate::util::{read_u16, read_u32};

struct HkdfLen(usize);
impl KeyType for HkdfLen {
    fn len(&self) -> usize {
        self.0
    }
}

const QUIC_INITIAL_MIN_LEN: usize = 128;
const QUIC_HP_SAMPLE_LEN: usize = 16;
const QUIC_TAG_LEN: usize = 16;
const QUIC_MAX_CID_LEN: usize = 20;
const QUIC_MAX_CRYPTO_LEN: usize = 64 * 1024;
const QUIC_FAKE_INITIAL_TARGET_LEN: usize = 1200;
const QUIC_FAKE_DCID: [u8; 8] = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
const QUIC_FAKE_SCID: [u8; 4] = [0x11, 0x22, 0x33, 0x44];
const QUIC_V1_SALT: [u8; 20] = [
    0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17, 0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f,
    0x0a,
];
const QUIC_V2_SALT: [u8; 20] = [
    0x0d, 0xed, 0xe3, 0xde, 0xf7, 0x00, 0xa6, 0xdb, 0x81, 0x93, 0x81, 0xbe, 0x6e, 0x26, 0x9d, 0xcb, 0xf9, 0xbd, 0x2e,
    0xd9,
];

#[derive(Debug, Clone, Copy)]
struct QuicInitialHeader<'a> {
    version: u32,
    dcid: &'a [u8],
    scid: &'a [u8],
    token: &'a [u8],
    payload_len: usize,
    pn_offset: usize,
}

fn is_quic_v2(version: u32) -> bool {
    version == QUIC_V2_VERSION
}

fn supported_quic_version(version: u32) -> bool {
    matches!(version, QUIC_V1_VERSION | QUIC_V2_VERSION)
}

fn quic_hkdf_label(label: &str, out_len: usize) -> Option<Vec<u8>> {
    if out_len > u16::MAX as usize || label.len() > u8::MAX as usize {
        return None;
    }
    let mut info = Vec::with_capacity(2 + 1 + label.len() + 1);
    info.extend_from_slice(&(out_len as u16).to_be_bytes());
    info.push(label.len() as u8);
    info.extend_from_slice(label.as_bytes());
    info.push(0);
    Some(info)
}

fn quic_expand_label(secret: &[u8], label: &str, out: &mut [u8]) -> Option<()> {
    let info = quic_hkdf_label(label, out.len())?;
    let prk = hkdf::Prk::new_less_safe(HKDF_SHA256, secret);
    let info_refs: &[&[u8]] = &[&info];
    let okm = prk.expand(info_refs, HkdfLen(out.len())).ok()?;
    okm.fill(out).ok()?;
    Some(())
}

fn quic_derive_client_initial_secret(dcid: &[u8], version: u32) -> Option<[u8; 32]> {
    let salt_bytes = match version {
        QUIC_V1_VERSION => &QUIC_V1_SALT,
        QUIC_V2_VERSION => &QUIC_V2_SALT,
        _ => return None,
    };
    let salt = Salt::new(HKDF_SHA256, salt_bytes);
    let prk = salt.extract(dcid);
    let mut secret = [0u8; 32];
    let info = quic_hkdf_label("tls13 client in", secret.len())?;
    let info_refs: &[&[u8]] = &[&info];
    let okm = prk.expand(info_refs, HkdfLen(secret.len())).ok()?;
    okm.fill(&mut secret).ok()?;
    Some(secret)
}

fn read_quic_varint(data: &[u8], offset: usize) -> Option<(u64, usize)> {
    let first = *data.get(offset)?;
    let len = 1usize << ((first >> 6) as usize);
    let bytes = data.get(offset..offset + len)?;
    let mut value = (bytes[0] & 0x3f) as u64;
    for byte in &bytes[1..] {
        value = (value << 8) | u64::from(*byte);
    }
    Some((value, len))
}

fn encode_quic_varint(value: u64) -> Vec<u8> {
    match value {
        0..=63 => vec![value as u8],
        64..=16_383 => ((0x4000 | value as u16).to_be_bytes()).to_vec(),
        16_384..=1_073_741_823 => ((0x8000_0000 | value as u32).to_be_bytes()).to_vec(),
        _ => {
            let mut bytes = value.to_be_bytes();
            bytes[0] |= 0xc0;
            bytes.to_vec()
        }
    }
}

fn append_quic_crypto_frame(out: &mut Vec<u8>, offset: u64, data: &[u8]) {
    out.push(0x06);
    out.extend_from_slice(&encode_quic_varint(offset));
    out.extend_from_slice(&encode_quic_varint(data.len() as u64));
    out.extend_from_slice(data);
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
fn build_quic_initial_raw(
    version: u32,
    dcid: &[u8],
    scid: &[u8],
    token: &[u8],
    mut plaintext: Vec<u8>,
    min_total_len: usize,
) -> Option<Vec<u8>> {
    let token_varint = encode_quic_varint(token.len() as u64);

    // Pad plaintext so the total packet reaches min_total_len.
    loop {
        let payload_len = 4 + plaintext.len() + QUIC_TAG_LEN;
        let payload_len_varint = encode_quic_varint(payload_len as u64);
        let header_len =
            1 + 4 + 1 + dcid.len() + 1 + scid.len() + token_varint.len() + payload_len_varint.len();
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

    let packet_number = [0u8; 4];
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
    let mut sample = GenericArray::clone_from_slice(ciphertext.get(..QUIC_HP_SAMPLE_LEN)?);
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

    build_quic_initial_raw(
        version,
        &QUIC_FAKE_DCID,
        &QUIC_FAKE_SCID,
        &[],
        plaintext,
        QUIC_FAKE_INITIAL_TARGET_LEN,
    )
}

fn padded_default_fake_tls_client_hello() -> Vec<u8> {
    let mut client_hello = DEFAULT_FAKE_TLS.to_vec();
    let target_len =
        read_u16(DEFAULT_FAKE_TLS, 3).map_or(client_hello.len(), |record_len| record_len + TLS_RECORD_HEADER_LEN);
    if client_hello.len() < target_len {
        client_hello.resize(target_len, 0);
    }
    client_hello
}

pub fn build_realistic_quic_initial(version: u32, host_override: Option<&str>) -> Option<Vec<u8>> {
    let mut client_hello = padded_default_fake_tls_client_hello();
    if let Some(host) = host_override {
        let capacity = client_hello.len().saturating_add(host.len()).saturating_add(64);
        let mutation = change_tls_sni_seeded_like_c(&client_hello, host.as_bytes(), capacity, 7);
        if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
            client_hello = mutation.bytes;
        }
    }
    build_quic_initial_from_tls(version, &client_hello, 0)
}

fn parse_quic_initial_header(buffer: &[u8]) -> Option<QuicInitialHeader<'_>> {
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

fn decrypt_quic_initial_payload(buffer: &[u8], header: QuicInitialHeader<'_>) -> Option<Vec<u8>> {
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
    let mut sample_block = GenericArray::clone_from_slice(sample);
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

fn defrag_quic_crypto_frames(payload: &[u8]) -> Option<(Vec<u8>, bool)> {
    let mut pieces = Vec::new();
    let mut cursor = 0usize;
    let mut max_end = 0usize;

    while cursor < payload.len() {
        match payload[cursor] {
            0x00 | 0x01 => {
                cursor += 1;
            }
            0x06 => {
                cursor += 1;
                let (offset, offset_len) = read_quic_varint(payload, cursor)?;
                cursor += offset_len;
                let (frame_len, frame_len_len) = read_quic_varint(payload, cursor)?;
                cursor += frame_len_len;
                let offset: usize = offset.try_into().ok()?;
                let frame_len: usize = frame_len.try_into().ok()?;
                let end = cursor.checked_add(frame_len)?;
                let chunk = payload.get(cursor..end)?;
                cursor = end;
                let piece_end = offset.checked_add(frame_len)?;
                if piece_end > QUIC_MAX_CRYPTO_LEN {
                    return None;
                }
                max_end = max_end.max(piece_end);
                pieces.push((offset, chunk.to_vec()));
            }
            _ => return None,
        }
    }

    if pieces.is_empty() || max_end == 0 {
        return None;
    }

    let mut data = vec![0u8; max_end];
    let mut covered = vec![false; max_end];
    for (offset, chunk) in pieces {
        let end = offset + chunk.len();
        data[offset..end].copy_from_slice(&chunk);
        covered[offset..end].fill(true);
    }

    Some((data, covered.iter().all(|covered| *covered)))
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

/// Re-encrypt a QUIC Initial packet with the TLS ClientHello split across
/// two CRYPTO frames at the given split offset within the ClientHello.
///
/// Returns `None` if the packet is not a valid QUIC Initial or cannot be parsed.
pub fn tamper_quic_initial_split_sni(packet: &[u8], split_offset: usize) -> Option<Vec<u8>> {
    let header = parse_quic_initial_header(packet)?;
    let payload = decrypt_quic_initial_payload(packet, header)?;
    let (client_hello, is_complete) = defrag_quic_crypto_frames(&payload)?;
    if !is_complete {
        return None;
    }
    if split_offset == 0 || split_offset >= client_hello.len() {
        return None;
    }

    let mut plaintext = Vec::new();
    append_quic_crypto_frame(&mut plaintext, 0, &client_hello[..split_offset]);
    append_quic_crypto_frame(&mut plaintext, split_offset as u64, &client_hello[split_offset..]);

    build_quic_initial_raw(
        header.version,
        header.dcid,
        header.scid,
        header.token,
        plaintext,
        QUIC_FAKE_INITIAL_TARGET_LEN,
    )
}

/// Replace the QUIC version field in a Long Header packet with a different version.
/// This prevents DPI from deriving the correct decryption keys.
///
/// Returns `None` if the packet doesn't have a valid QUIC Long Header.
pub fn tamper_quic_version(packet: &[u8], fake_version: u32) -> Option<Vec<u8>> {
    if packet.len() < 5 || (packet[0] & 0x80) == 0 {
        return None;
    }
    let mut tampered = packet.to_vec();
    tampered[1..5].copy_from_slice(&fake_version.to_be_bytes());
    Some(tampered)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[allow(dead_code)]
    mod rust_packet_seeds {
        use crate as ripdpi_packets;

        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/rust_packet_seeds.rs"));
    }

    #[test]
    fn parse_quic_initial_extracts_v1_sni() {
        let packet =
            build_realistic_quic_initial(QUIC_V1_VERSION, Some("docs.example.test")).expect("build quic initial v1");
        let parsed = parse_quic_initial(&packet).expect("parse quic initial v1");

        assert!(is_quic_initial(&packet));
        assert_eq!(parsed.version, QUIC_V1_VERSION);
        assert!(parsed.is_crypto_complete);
        assert_eq!(parsed.host(), b"docs.example.test");
    }

    #[test]
    fn parse_quic_initial_extracts_v2_sni() {
        let packet =
            build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("build quic initial v2");
        let parsed = parse_quic_initial(&packet).expect("parse quic initial v2");

        assert!(is_quic_initial(&packet));
        assert_eq!(parsed.version, QUIC_V2_VERSION);
        assert!(parsed.is_crypto_complete);
        assert_eq!(parsed.host(), b"media.example.test");
    }

    #[test]
    fn realistic_quic_fake_builder_round_trips_and_uses_default_tls_base() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build default realistic fake");
        let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

        assert_eq!(parsed.version, QUIC_V1_VERSION);
        assert_eq!(parsed.host(), b"www.wikipedia.org");
        assert_eq!(packet.len(), QUIC_FAKE_INITIAL_TARGET_LEN);
    }

    #[test]
    fn realistic_quic_fake_builder_applies_host_override() {
        let packet =
            build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build realistic fake");
        let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

        assert_eq!(parsed.host(), b"video.example.test");
    }

    #[test]
    fn realistic_quic_fake_builder_defaults_to_v1_for_unknown_versions() {
        let packet =
            build_realistic_quic_initial(0xface_feed, Some("video.example.test")).expect("build realistic fake");
        let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

        assert_eq!(parsed.version, QUIC_V1_VERSION);
        assert_eq!(parsed.host(), b"video.example.test");
    }

    #[test]
    fn compat_default_quic_fake_matches_fixed_compatibility_layout() {
        let packet = default_fake_quic_compat();

        assert_eq!(packet.len(), DEFAULT_FAKE_QUIC_COMPAT_LEN);
        assert_eq!(packet[0], 0x40);
        assert!(packet[1..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn parse_quic_initial_rejects_unsupported_versions() {
        let mut packet = rust_packet_seeds::quic_initial_v1();
        packet[1..5].copy_from_slice(&0x0000_0002u32.to_be_bytes());

        assert!(!is_quic_initial(&packet));
        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_bad_tags() {
        let mut packet = rust_packet_seeds::quic_initial_v1();
        let last = packet.len() - 1;
        packet[last] ^= 0xff;

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_truncated_packets() {
        let mut packet = rust_packet_seeds::quic_initial_v1();
        packet.truncate(packet.len() - 32);

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_incomplete_crypto_frames() {
        let packet = rust_packet_seeds::quic_initial_with_crypto_gap(QUIC_V1_VERSION, "docs.example.test");

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_missing_sni() {
        let packet = rust_packet_seeds::quic_initial_missing_sni(QUIC_V1_VERSION);

        assert!(parse_quic_initial(&packet).is_none());
    }

    // ---- Key derivation and label unit tests ----

    #[test]
    fn quic_hkdf_label_produces_correct_binary_format() {
        let label = quic_hkdf_label("tls13 quic key", 16).expect("label");
        // First 2 bytes: output length as u16 big-endian
        assert_eq!(&label[..2], &16u16.to_be_bytes());
        // Next byte: label length
        assert_eq!(label[2], 14); // "tls13 quic key".len()
                                  // Then the label bytes
        assert_eq!(&label[3..17], b"tls13 quic key");
        // Final byte: empty context (0 length)
        assert_eq!(label[17], 0);
        assert_eq!(label.len(), 18);
    }

    #[test]
    fn quic_hkdf_label_rejects_oversized_output() {
        assert!(quic_hkdf_label("x", u16::MAX as usize + 1).is_none());
    }

    #[test]
    fn quic_v1_initial_secret_is_deterministic() {
        let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
        let s1 = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1 secret first");
        let s2 = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1 secret second");
        assert_eq!(s1, s2);
    }

    #[test]
    fn quic_v1_and_v2_produce_different_secrets_for_same_dcid() {
        let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
        let v1 = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1");
        let v2 = quic_derive_client_initial_secret(&dcid, QUIC_V2_VERSION).expect("v2");
        assert_ne!(v1, v2);
    }

    #[test]
    fn quic_derive_rejects_unsupported_version() {
        let dcid = [0x83, 0x94, 0xc8, 0xf0];
        assert!(quic_derive_client_initial_secret(&dcid, 0xdead_beef).is_none());
    }

    #[test]
    fn quic_expand_label_produces_correct_sizes() {
        let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
        let secret = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("secret");

        let mut key = [0u8; 16];
        let mut iv = [0u8; 12];
        let mut hp = [0u8; 16];
        quic_expand_label(&secret, "tls13 quic key", &mut key).expect("key");
        quic_expand_label(&secret, "tls13 quic iv", &mut iv).expect("iv");
        quic_expand_label(&secret, "tls13 quic hp", &mut hp).expect("hp");

        // key, iv, hp should all be non-zero (overwhelmingly unlikely to be all zeros)
        assert!(key.iter().any(|b| *b != 0), "key should not be all zeros");
        assert!(iv.iter().any(|b| *b != 0), "iv should not be all zeros");
        assert!(hp.iter().any(|b| *b != 0), "hp should not be all zeros");
        // key and hp are different despite same length
        assert_ne!(key, hp, "key and hp should differ");
    }

    #[test]
    fn quic_v1_initial_secret_matches_rfc_9001_appendix_a() {
        // RFC 9001, Section A.1: Initial Keys
        // DCID = 0x8394c8f03e515708
        let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
        let secret = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1 secret");

        // Expected client_initial_secret from RFC 9001, Appendix A.1:
        let expected = [
            0xc0, 0x0c, 0xf1, 0x51, 0xca, 0x5b, 0xe0, 0x75, 0xed, 0x0e, 0xbf, 0xb5, 0xc8, 0x03, 0x23, 0xc4, 0x2d, 0x6b,
            0x7d, 0xb6, 0x78, 0x81, 0x28, 0x9a, 0xf4, 0x00, 0x8f, 0x1f, 0x6c, 0x35, 0x7a, 0xea,
        ];
        assert_eq!(secret, expected, "client initial secret must match RFC 9001 Appendix A.1");
    }

    #[test]
    fn quic_v2_uses_different_label_namespace() {
        let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
        let v1_secret = quic_derive_client_initial_secret(&dcid, QUIC_V1_VERSION).expect("v1");
        let v2_secret = quic_derive_client_initial_secret(&dcid, QUIC_V2_VERSION).expect("v2");

        let mut v1_key = [0u8; 16];
        let mut v2_key = [0u8; 16];
        quic_expand_label(&v1_secret, "tls13 quic key", &mut v1_key).expect("v1 key");
        quic_expand_label(&v2_secret, "tls13 quicv2 key", &mut v2_key).expect("v2 key");
        assert_ne!(v1_key, v2_key);
    }

    #[test]
    fn different_dcids_produce_different_secrets() {
        let dcid_a = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08];
        let dcid_b = [0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18];
        let sa = quic_derive_client_initial_secret(&dcid_a, QUIC_V1_VERSION).expect("a");
        let sb = quic_derive_client_initial_secret(&dcid_b, QUIC_V1_VERSION).expect("b");
        assert_ne!(sa, sb);
    }

    // ---- QUIC varint codec unit tests ----

    #[test]
    fn read_quic_varint_decodes_1_byte_value() {
        // 0x25 = 0b00_100101, prefix 00 -> 1-byte, value = 37
        assert_eq!(read_quic_varint(&[0x25], 0), Some((37, 1)));
    }

    #[test]
    fn read_quic_varint_decodes_2_byte_value() {
        // 0x7bbd = 0b01_111011_10111101, prefix 01 -> 2-byte, value = 15293
        assert_eq!(read_quic_varint(&[0x7b, 0xbd], 0), Some((15293, 2)));
    }

    #[test]
    fn read_quic_varint_decodes_4_byte_value() {
        // 0x9d7f3e7d, prefix 10 -> 4-byte, value = 494878333
        assert_eq!(read_quic_varint(&[0x9d, 0x7f, 0x3e, 0x7d], 0), Some((494878333, 4)));
    }

    #[test]
    fn read_quic_varint_decodes_8_byte_value() {
        // 0xc2197c5eff14e88c, prefix 11 -> 8-byte, value = 151288809941952652
        assert_eq!(
            read_quic_varint(&[0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c], 0),
            Some((151288809941952652, 8))
        );
    }

    #[test]
    fn read_quic_varint_respects_offset() {
        assert_eq!(read_quic_varint(&[0xff, 0x25], 1), Some((37, 1)));
    }

    #[test]
    fn read_quic_varint_returns_none_for_empty_slice() {
        assert_eq!(read_quic_varint(&[], 0), None);
    }

    #[test]
    fn read_quic_varint_returns_none_for_truncated_2_byte() {
        assert_eq!(read_quic_varint(&[0x40], 0), None);
    }

    #[test]
    fn read_quic_varint_returns_none_for_offset_beyond_slice() {
        assert_eq!(read_quic_varint(&[0x25], 5), None);
    }

    #[test]
    fn encode_quic_varint_1_byte_boundaries() {
        assert_eq!(encode_quic_varint(0), vec![0x00]);
        assert_eq!(encode_quic_varint(63), vec![0x3f]);
    }

    #[test]
    fn encode_quic_varint_2_byte_boundaries() {
        assert_eq!(encode_quic_varint(64), vec![0x40, 0x40]);
        assert_eq!(encode_quic_varint(16383), vec![0x7f, 0xff]);
    }

    #[test]
    fn encode_quic_varint_4_byte_boundaries() {
        assert_eq!(encode_quic_varint(16384), vec![0x80, 0x00, 0x40, 0x00]);
        assert_eq!(encode_quic_varint(1_073_741_823), vec![0xbf, 0xff, 0xff, 0xff]);
    }

    #[test]
    fn encode_quic_varint_8_byte() {
        let encoded = encode_quic_varint(1_073_741_824);
        assert_eq!(encoded.len(), 8);
        assert_eq!(encoded[0] & 0xc0, 0xc0);
    }

    #[test]
    fn quic_varint_round_trips() {
        for value in [0, 1, 63, 64, 16383, 16384, 1_073_741_823, 1_073_741_824, u64::MAX >> 2] {
            let encoded = encode_quic_varint(value);
            let (decoded, len) = read_quic_varint(&encoded, 0).expect("round-trip decode");
            assert_eq!(decoded, value, "round-trip failed for {value}");
            assert_eq!(len, encoded.len());
        }
    }

    // ---- QUIC crypto frame defragmentation tests ----

    fn make_crypto_frame(offset: u64, data: &[u8]) -> Vec<u8> {
        let mut frame = Vec::new();
        append_quic_crypto_frame(&mut frame, offset, data);
        frame
    }

    #[test]
    fn defrag_single_crypto_frame() {
        let frame = make_crypto_frame(0, b"hello");
        let (data, complete) = defrag_quic_crypto_frames(&frame).expect("single frame");
        assert!(complete);
        assert_eq!(data, b"hello");
    }

    #[test]
    fn defrag_two_contiguous_frames() {
        let mut payload = make_crypto_frame(0, b"hel");
        payload.extend(make_crypto_frame(3, b"lo"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("two frames");
        assert!(complete);
        assert_eq!(data, b"hello");
    }

    #[test]
    fn defrag_frames_with_gap_reports_incomplete() {
        let mut payload = make_crypto_frame(0, b"AB");
        payload.extend(make_crypto_frame(4, b"EF"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("gap");
        assert!(!complete);
        assert_eq!(data.len(), 6);
        assert_eq!(&data[0..2], b"AB");
        assert_eq!(&data[4..6], b"EF");
    }

    #[test]
    fn defrag_skips_padding_frames() {
        let mut payload = vec![0x00, 0x00, 0x01];
        payload.extend(make_crypto_frame(0, b"data"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("with padding");
        assert!(complete);
        assert_eq!(data, b"data");
    }

    #[test]
    fn defrag_rejects_empty_payload() {
        assert!(defrag_quic_crypto_frames(&[]).is_none());
    }

    #[test]
    fn defrag_rejects_only_padding() {
        assert!(defrag_quic_crypto_frames(&[0x00, 0x00, 0x01]).is_none());
    }

    #[test]
    fn defrag_rejects_unknown_frame_type() {
        let mut payload = make_crypto_frame(0, b"ok");
        payload.push(0x42);
        assert!(defrag_quic_crypto_frames(&payload).is_none());
    }

    #[test]
    fn defrag_rejects_oversized_crypto_offset() {
        let frame = make_crypto_frame(65530, &[0u8; 10]);
        assert!(defrag_quic_crypto_frames(&frame).is_none());
    }

    // ---- DPI evasion tamper function tests ----

    #[test]
    fn tamper_quic_version_replaces_version_field() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build packet");
        let tampered = tamper_quic_version(&packet, 0x1a2a3a4a).expect("tamper version");

        assert_eq!(&tampered[1..5], &[0x1a, 0x2a, 0x3a, 0x4a]);
        // DPI cannot decrypt with the wrong version salt
        assert!(parse_quic_initial(&tampered).is_none());
    }

    #[test]
    fn tamper_quic_initial_split_sni_produces_valid_packet() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build packet");
        let original = parse_quic_initial(&packet).expect("parse original");
        let split_offset = original.tls_info.host_start;

        let tampered = tamper_quic_initial_split_sni(&packet, split_offset).expect("tamper split");
        let reparsed = parse_quic_initial(&tampered).expect("parse tampered");

        assert_eq!(reparsed.client_hello, original.client_hello);
    }

    #[test]
    fn tamper_quic_version_returns_none_for_short_header() {
        // Short header: bit 7 = 0
        let packet = vec![0x40, 0x01, 0x02, 0x03, 0x04, 0x05];
        assert!(tamper_quic_version(&packet, 0x1a2a3a4a).is_none());
    }

    // ---- Additional tamper edge case tests ----

    #[test]
    fn tamper_quic_version_returns_none_for_too_short() {
        assert!(tamper_quic_version(&[0xc0, 0x01, 0x02], 0xdead).is_none());
        assert!(tamper_quic_version(&[], 0xdead).is_none());
    }

    #[test]
    fn tamper_quic_version_preserves_packet_length() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build");
        let tampered = tamper_quic_version(&packet, 0xffff_ffff).expect("tamper");
        assert_eq!(tampered.len(), packet.len());
    }

    #[test]
    fn tamper_quic_initial_split_sni_rejects_zero_offset() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build");
        assert!(tamper_quic_initial_split_sni(&packet, 0).is_none());
    }

    #[test]
    fn tamper_quic_initial_split_sni_rejects_offset_beyond_payload() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build");
        let parsed = parse_quic_initial(&packet).expect("parse");
        assert!(tamper_quic_initial_split_sni(&packet, parsed.client_hello.len()).is_none());
    }

    #[test]
    fn tamper_quic_initial_split_sni_v2_round_trips() {
        let packet = build_realistic_quic_initial(QUIC_V2_VERSION, Some("test.example.org")).expect("build v2");
        let original = parse_quic_initial(&packet).expect("parse original");
        let split_offset = original.tls_info.host_start;

        let tampered = tamper_quic_initial_split_sni(&packet, split_offset).expect("tamper v2");
        let reparsed = parse_quic_initial(&tampered).expect("reparse v2");
        assert_eq!(reparsed.version, QUIC_V2_VERSION);
        assert_eq!(reparsed.client_hello, original.client_hello);
    }

    #[test]
    fn build_quic_initial_from_tls_rejects_non_tls() {
        assert!(build_quic_initial_from_tls(QUIC_V1_VERSION, b"not tls", 0).is_none());
    }

    #[test]
    fn build_quic_initial_from_tls_rejects_empty() {
        assert!(build_quic_initial_from_tls(QUIC_V1_VERSION, &[], 0).is_none());
    }

    #[test]
    fn defrag_overlapping_frames_uses_last_write_wins() {
        // Two frames that overlap: [0..3] "ABC" and [1..4] "XYZ"
        let mut payload = make_crypto_frame(0, b"ABC");
        payload.extend(make_crypto_frame(1, b"XYZ"));
        let (data, complete) = defrag_quic_crypto_frames(&payload).expect("overlap");
        assert!(complete);
        assert_eq!(data.len(), 4);
        // Second frame overwrites bytes 1..4
        assert_eq!(&data[1..4], b"XYZ");
    }

    #[test]
    fn parse_quic_initial_header_rejects_empty_dcid() {
        // Manually craft a packet with dcid_len=0
        let mut packet = vec![0xc3]; // Long header, Initial type
        packet.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
        packet.push(0); // dcid_len = 0 (should be rejected)
        packet.resize(QUIC_INITIAL_MIN_LEN, 0);
        assert!(parse_quic_initial_header(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_header_rejects_oversized_dcid() {
        let mut packet = vec![0xc3];
        packet.extend_from_slice(&QUIC_V1_VERSION.to_be_bytes());
        packet.push(21); // dcid_len = 21 > QUIC_MAX_CID_LEN
        packet.resize(QUIC_INITIAL_MIN_LEN, 0);
        assert!(parse_quic_initial_header(&packet).is_none());
    }
}
