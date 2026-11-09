use crate::types::{QUIC_V2_VERSION, QuicCryptoFrameInfo};
use crate::util::read_u32;

use super::QUIC_MAX_CID_LEN;
use super::frames::{collect_quic_crypto_frames, defrag_quic_crypto_frames, read_quic_varint};
use super::parse::{
    QuicInitialHeader, decrypt_quic_initial_payload_with_offset, parse_quic_initial_header,
    parse_quic_initial_response_header, validate_complete_quic_client_hello,
};

pub(super) struct DecodedQuicInitial<'a> {
    pub(super) header: QuicInitialHeader<'a>,
    pub(super) client_hello: Vec<u8>,
    pub(super) ciphertext_payload_offset: usize,
    pub(super) crypto_frames: Vec<QuicCryptoFrameInfo>,
}

pub(super) fn decode_quic_initial_datagram(buffer: &[u8]) -> Option<DecodedQuicInitial<'_>> {
    if buffer.len() > u16::MAX as usize {
        return None;
    }
    let header = parse_quic_initial_header(buffer)?;
    let initial_type = if header.version == QUIC_V2_VERSION { 0x10 } else { 0x00 };
    let retry_type = if header.version == QUIC_V2_VERSION { 0x00 } else { 0x30 };
    let mut cursor = 0usize;
    let mut plaintext = Vec::new();
    let mut crypto_frames = Vec::new();
    let mut ciphertext_payload_offset = None;
    while cursor < buffer.len() {
        let packet = buffer.get(cursor..)?;
        let first = *packet.first()?;
        if first & 0x80 == 0 {
            break; // A short-header packet consumes the rest of the datagram.
        }
        if first & 0xc0 != 0xc0 || read_u32(packet, 1)? != header.version {
            return None;
        }
        let packet_type = first & 0x30;
        if packet_type == retry_type {
            break; // Retry has no Length field, so it cannot expose a later packet.
        }
        if packet_type != initial_type {
            cursor = cursor.checked_add(protected_long_packet_len(packet)?)?;
            continue;
        }
        let next = parse_quic_initial_response_header(packet)?;
        if next.dcid != header.dcid || next.scid != header.scid || next.token != header.token {
            return None;
        }
        let (payload, offset) = decrypt_quic_initial_payload_with_offset(packet, next)?;
        let wire_offset = cursor.checked_add(offset)?;
        let base = *ciphertext_payload_offset.get_or_insert(wire_offset);
        for mut frame in collect_quic_crypto_frames(&payload)? {
            // Later packet headers, tags and padding must stay in wire marker coordinates.
            frame.data_offset = wire_offset.checked_sub(base)?.checked_add(frame.data_offset)?;
            crypto_frames.push(frame);
        }
        plaintext.extend_from_slice(&payload);
        cursor = cursor.checked_add(next.pn_offset.checked_add(next.payload_len)?)?;
    }
    let (client_hello, complete) = defrag_quic_crypto_frames(&plaintext)?;
    if !complete {
        return None;
    }
    validate_complete_quic_client_hello(&client_hello)?;
    Some(DecodedQuicInitial {
        header,
        client_hello,
        ciphertext_payload_offset: ciphertext_payload_offset?,
        crypto_frames,
    })
}

fn protected_long_packet_len(buffer: &[u8]) -> Option<usize> {
    let dcid_len = usize::from(*buffer.get(5)?);
    if dcid_len > QUIC_MAX_CID_LEN {
        return None;
    }
    let scid_offset = 6 + dcid_len;
    let scid_len = usize::from(*buffer.get(scid_offset)?);
    if scid_len > QUIC_MAX_CID_LEN {
        return None;
    }
    let length_offset = scid_offset + 1 + scid_len;
    let (length, width) = read_quic_varint(buffer, length_offset)?;
    if length < 20 {
        return None;
    }
    let end = length_offset.checked_add(width)?.checked_add(length.try_into().ok()?)?;
    buffer.get(..end)?;
    Some(end)
}
