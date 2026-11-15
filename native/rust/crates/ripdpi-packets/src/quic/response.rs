mod compatible;

pub use compatible::{
    QuicInitialResponseContext, QuicInitialResponseDisposition, classify_quic_initial_response_context,
    parse_quic_initial_response_context,
};

use ring::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};

use super::crypto::quic_derive_initial_secret;
use super::frames::{next_quic_varint, skip_quic_ack};
use super::parse::{decrypt_quic_initial_with_secret, parse_quic_initial_response_header};
use crate::types::{QUIC_V1_VERSION, QUIC_V2_VERSION};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicResponseKind {
    Initial,
    Retry,
    VersionNegotiation,
}

/// The evidence carried by a valid first-flight reply to a connectivity probe.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicProbeResponseKind {
    InitialCrypto,
    InitialAck,
    InitialOther,
    InitialClose,
    Retry,
    VersionNegotiation,
}

enum CorrelatedResponse {
    Initial(Vec<u8>),
    Retry,
    VersionNegotiation,
}

/// Check response correlation and packet integrity for one Initial probe.
/// This proves a QUIC response, not a completed or authenticated TLS handshake.
/// Initial and Retry keys are public; an observer of the request can forge them.
pub fn validate_quic_response(request: &[u8], response: &[u8]) -> Option<QuicResponseKind> {
    let kind = match correlate_response(request, response)? {
        CorrelatedResponse::Initial(_) => QuicResponseKind::Initial,
        CorrelatedResponse::Retry => QuicResponseKind::Retry,
        CorrelatedResponse::VersionNegotiation => QuicResponseKind::VersionNegotiation,
    };
    Some(kind)
}

/// Check correlation, packet protection and permitted Initial frames in one UDP datagram.
/// Treat the result as first-flight evidence: the public Initial and Retry keys do not authenticate the peer.
pub fn validate_quic_probe_response(request: &[u8], response: &[u8]) -> Option<QuicProbeResponseKind> {
    match correlate_response(request, response)? {
        CorrelatedResponse::Initial(payload) => classify_probe_initial(&payload, response.len()),
        CorrelatedResponse::Retry => Some(QuicProbeResponseKind::Retry),
        CorrelatedResponse::VersionNegotiation => Some(QuicProbeResponseKind::VersionNegotiation),
    }
}

fn correlate_response(request: &[u8], response: &[u8]) -> Option<CorrelatedResponse> {
    let sent = parse_quic_initial_response_header(request)?;
    let (version, dcid, scid, offset) = long_header(response)?;
    if dcid != sent.scid {
        return None;
    }
    if version == 0 {
        return validate_quic_version_negotiation_response(request, response)
            .then_some(CorrelatedResponse::VersionNegotiation);
    }
    if version != sent.version || response[0] & 0x40 == 0 {
        return None;
    }
    let retry_type = match version {
        QUIC_V1_VERSION => 0x30,
        QUIC_V2_VERSION => 0x00,
        _ => return None,
    };
    if response[0] & 0x30 == retry_type {
        if scid == sent.dcid || response.len().checked_sub(offset)? <= 16 {
            return None;
        }
        validate_retry_tag(version, sent.dcid, response)?;
        return Some(CorrelatedResponse::Retry);
    }
    let header = parse_quic_initial_response_header(response)?;
    decrypt_server_initial(sent.dcid, response, header).map(CorrelatedResponse::Initial)
}

fn decrypt_server_initial(
    request_dcid: &[u8],
    response: &[u8],
    header: super::parse::QuicInitialHeader<'_>,
) -> Option<Vec<u8>> {
    if !header.token.is_empty() {
        return None;
    }
    let secret = quic_derive_initial_secret(request_dcid, header.version, "tls13 server in")?;
    decrypt_quic_initial_with_secret(response, header, &secret).map(|(payload, _)| payload)
}

fn classify_probe_initial(payload: &[u8], datagram_len: usize) -> Option<QuicProbeResponseKind> {
    // RFC 9000 sections 12.4 and 14.1: the 1200-byte minimum is for the UDP datagram
    // carrying an ack-eliciting server Initial, not the individual packet.
    const MIN_DATAGRAM_LEN: usize = 1200;
    if payload.is_empty() {
        return None;
    }
    let mut cursor = 0;
    let mut ack = false;
    let mut crypto = false;
    let mut close = false;
    let mut ack_eliciting = false;
    while let Some(&frame_type) = payload.get(cursor) {
        cursor += 1;
        match frame_type {
            0x00 => {}
            0x01 => ack_eliciting = true,
            0x02 | 0x03 => {
                skip_quic_ack(payload, &mut cursor, frame_type == 0x03)?;
                ack = true;
            }
            0x06 => {
                let offset = next_quic_varint(payload, &mut cursor)?;
                let len = next_quic_varint(payload, &mut cursor)?;
                if offset + len >= 1 << 62 {
                    return None;
                }
                skip_probe_frame_data(payload, &mut cursor, len)?;
                ack_eliciting = true;
                crypto |= len != 0;
            }
            0x1c => {
                next_quic_varint(payload, &mut cursor)?; // Error code.
                next_quic_varint(payload, &mut cursor)?; // Triggering frame type.
                let len = next_quic_varint(payload, &mut cursor)?;
                skip_probe_frame_data(payload, &mut cursor, len)?;
                close = true;
            }
            _ => return None,
        }
    }

    if ack_eliciting && datagram_len < MIN_DATAGRAM_LEN {
        None
    } else if close {
        Some(QuicProbeResponseKind::InitialClose)
    } else if crypto {
        Some(QuicProbeResponseKind::InitialCrypto)
    } else if ack && !ack_eliciting {
        Some(QuicProbeResponseKind::InitialAck)
    } else {
        Some(QuicProbeResponseKind::InitialOther)
    }
}

fn skip_probe_frame_data(payload: &[u8], cursor: &mut usize, len: u64) -> Option<()> {
    let end = cursor.checked_add(usize::try_from(len).ok()?)?;
    payload.get(*cursor..end)?;
    *cursor = end;
    Some(())
}

/// Check the echoed connection IDs and version list of a Version Negotiation reply.
/// The request may use a reserved version; its version-specific payload is not parsed.
pub fn validate_quic_version_negotiation_response(request: &[u8], response: &[u8]) -> bool {
    let Some((sent_version, sent_dcid, sent_scid, _)) = long_header(request) else {
        return false;
    };
    let Some((version, dcid, scid, offset)) = long_header(response) else {
        return false;
    };
    let versions = &response[offset..];
    sent_version != 0
        && version == 0
        && dcid == sent_scid
        && scid == sent_dcid
        && !versions.is_empty()
        && versions.len().is_multiple_of(4)
        && !versions.as_chunks::<4>().0.iter().any(|value| *value == sent_version.to_be_bytes() || *value == [0; 4])
}

fn long_header(packet: &[u8]) -> Option<(u32, &[u8], &[u8], usize)> {
    if packet.first()? & 0x80 == 0 {
        return None;
    }
    let version = u32::from_be_bytes(packet.get(1..5)?.try_into().ok()?);
    let dcid_len = usize::from(*packet.get(5)?);
    let dcid = packet.get(6..6 + dcid_len)?;
    let offset = 6 + dcid_len;
    let scid_len = usize::from(*packet.get(offset)?);
    if version != 0 && (dcid_len > 20 || scid_len > 20) {
        return None;
    }
    let scid = packet.get(offset + 1..offset + 1 + scid_len)?;
    Some((version, dcid, scid, offset + 1 + scid_len))
}

fn validate_retry_tag(version: u32, original_dcid: &[u8], packet: &[u8]) -> Option<()> {
    // RFC 9001 section 5.8 and RFC 9369 section 3.3.3.
    let (key, nonce) = match version {
        QUIC_V1_VERSION => (
            [0xbe, 0x0c, 0x69, 0x0b, 0x9f, 0x66, 0x57, 0x5a, 0x1d, 0x76, 0x6b, 0x54, 0xe3, 0x68, 0xc8, 0x4e],
            [0x46, 0x15, 0x99, 0xd3, 0x5d, 0x63, 0x2b, 0xf2, 0x23, 0x98, 0x25, 0xbb],
        ),
        QUIC_V2_VERSION => (
            [0x8f, 0xb4, 0xb0, 0x1b, 0x56, 0xac, 0x48, 0xe2, 0x60, 0xfb, 0xcb, 0xce, 0xad, 0x7c, 0xcc, 0x92],
            [0xd8, 0x69, 0x69, 0xbc, 0x2d, 0x7c, 0x6d, 0x99, 0x90, 0xef, 0xb0, 0x4a],
        ),
        _ => return None,
    };
    let tag_offset = packet.len().checked_sub(16)?;
    let mut aad = Vec::with_capacity(1 + original_dcid.len() + tag_offset);
    aad.push(original_dcid.len().try_into().ok()?);
    aad.extend_from_slice(original_dcid);
    aad.extend_from_slice(&packet[..tag_offset]);
    let mut tag = packet[tag_offset..].to_vec();
    let key = LessSafeKey::new(UnboundKey::new(&AES_128_GCM, &key).ok()?);
    key.open_in_place(Nonce::assume_unique_for_key(nonce), Aad::from(aad), &mut tag).ok()?;
    Some(())
}

#[cfg(test)]
mod tests;
