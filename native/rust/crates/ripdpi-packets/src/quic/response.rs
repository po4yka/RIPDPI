mod compatible;

pub use compatible::{
    QuicInitialResponseContext, QuicInitialResponseDisposition, classify_quic_initial_response_context,
    parse_quic_initial_response_context,
};

use ring::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};

use super::crypto::quic_derive_initial_secret;
use super::parse::{decrypt_quic_initial_with_secret, parse_quic_initial_response_header};
use crate::types::{QUIC_V1_VERSION, QUIC_V2_VERSION};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicResponseKind {
    Initial,
    Retry,
    VersionNegotiation,
}

/// Check response correlation and packet integrity for one Initial probe.
/// This proves a QUIC response, not a completed or authenticated TLS handshake.
/// Initial and Retry keys are public; an observer of the request can forge them.
pub fn validate_quic_response(request: &[u8], response: &[u8]) -> Option<QuicResponseKind> {
    let sent = parse_quic_initial_response_header(request)?;
    let (version, dcid, scid, offset) = long_header(response)?;
    if dcid != sent.scid {
        return None;
    }
    if version == 0 {
        return validate_quic_version_negotiation_response(request, response)
            .then_some(QuicResponseKind::VersionNegotiation);
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
        return Some(QuicResponseKind::Retry);
    }
    validate_server_initial(sent.dcid, response)?;
    Some(QuicResponseKind::Initial)
}

fn validate_server_initial(request_dcid: &[u8], response: &[u8]) -> Option<()> {
    validate_server_initial_with_header(request_dcid, response, parse_quic_initial_response_header(response)?)
}

fn validate_server_initial_with_header(
    request_dcid: &[u8],
    response: &[u8],
    header: super::parse::QuicInitialHeader<'_>,
) -> Option<()> {
    if !header.token.is_empty() {
        return None;
    }
    let secret = quic_derive_initial_secret(request_dcid, header.version, "tls13 server in")?;
    decrypt_quic_initial_with_secret(response, header, &secret)?;
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
