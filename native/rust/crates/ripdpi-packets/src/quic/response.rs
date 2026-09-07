use ring::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};

use super::crypto::quic_derive_initial_secret;
use super::parse::{decrypt_quic_initial_with_secret, parse_quic_initial_response_header};
use super::{QUIC_FAKE_INITIAL_TARGET_LEN, build_browser_like_quic_initial_seed, packetize_quic_initial};
use crate::types::{QUIC_V1_VERSION, QUIC_V2_VERSION, QuicInitialBrowserProfile, QuicInitialPacketLayout};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicResponseKind {
    Initial,
    Retry,
    VersionNegotiation,
}

/// Build an active probe with fresh connection IDs. Fake/desync templates remain fixed.
pub fn build_probe_quic_initial(version: u32, host: Option<&str>) -> Option<Vec<u8>> {
    let mut seed = build_browser_like_quic_initial_seed(version, host, QuicInitialBrowserProfile::ChromeAndroid)?;
    let random = SystemRandom::new();
    random.fill(&mut seed.dcid).ok()?;
    random.fill(&mut seed.scid).ok()?;
    packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(QUIC_FAKE_INITIAL_TARGET_LEN))
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
        let versions = response.get(offset..)?;
        if scid != sent.dcid || versions.is_empty() || versions.len() % 4 != 0 {
            return None;
        }
        if versions.as_chunks::<4>().0.iter().any(|value| *value == sent.version.to_be_bytes() || *value == [0; 4]) {
            return None;
        }
        return Some(QuicResponseKind::VersionNegotiation);
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
    let header = parse_quic_initial_response_header(response)?;
    if !header.token.is_empty() {
        return None;
    }
    let secret = quic_derive_initial_secret(sent.dcid, version, "tls13 server in")?;
    decrypt_quic_initial_with_secret(response, header, &secret)?;
    Some(QuicResponseKind::Initial)
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
