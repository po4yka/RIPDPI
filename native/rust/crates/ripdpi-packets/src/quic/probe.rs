use std::net::IpAddr;

use ring::agreement;
use ring::rand::{SecureRandom, SystemRandom};

use super::frames::encode_quic_varint;
use super::parse::supported_quic_version;
use super::{QUIC_FAKE_INITIAL_TARGET_LEN, packetize_quic_initial};
use crate::types::{QUIC_V1_VERSION, QuicInitialPacketLayout, QuicInitialSeed};
use crate::util::read_u16;
use crate::{TlsFakeProfile, tls_fake_profile_bytes};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicProbeProfile {
    Chrome,
    Firefox,
    Generic,
}

/// Build a Chrome-shaped active Initial probe. Fake templates stay unchanged.
pub fn build_probe_quic_initial(version: u32, host: Option<&str>) -> Option<Vec<u8>> {
    build_profiled_probe_quic_initial(version, host, QuicProbeProfile::Chrome)
}

/// Build an active QUIC ClientHello with fresh connection IDs, random and key shares.
pub fn build_profiled_probe_quic_initial(
    version: u32,
    host: Option<&str>,
    profile: QuicProbeProfile,
) -> Option<Vec<u8>> {
    let random = SystemRandom::new();
    let mut dcid = vec![0; 8];
    let mut scid = vec![0; 4];
    random.fill(&mut dcid).ok()?;
    random.fill(&mut scid).ok()?;
    let tls_profile = match profile {
        QuicProbeProfile::Chrome => TlsFakeProfile::GoogleChrome,
        QuicProbeProfile::Firefox => TlsFakeProfile::IanaFirefox,
        QuicProbeProfile::Generic => TlsFakeProfile::CompatDefault,
    };
    let seed = QuicInitialSeed {
        version: if supported_quic_version(version) { version } else { QUIC_V1_VERSION },
        dcid,
        client_hello: probe_client_hello(tls_fake_profile_bytes(tls_profile), host, &scid, &random)?,
        scid,
        token: Vec::new(),
    };
    packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(QUIC_FAKE_INITIAL_TARGET_LEN))
}

pub(super) fn probe_client_hello(
    template: &[u8],
    host: Option<&str>,
    scid: &[u8],
    random: &SystemRandom,
) -> Option<Vec<u8>> {
    let mut template = template.to_vec();
    template.resize(template.len().max(read_u16(&template, 3)?.checked_add(5)?), 0);
    let parsed = crate::tls_nom::parse_client_hello_record(&template)?;
    let sid_end = 44usize.checked_add(usize::from(*template.get(43)?))?;
    let mut body = template.get(9..43)?.to_vec();
    random.fill(body.get_mut(2..34)?).ok()?;
    body.push(0); // RFC 9001 section 8.4 forbids a compatibility session ID.
    body.extend_from_slice(template.get(sid_end..parsed.ext_len_offset)?);

    let mut extensions = Vec::new();
    let mut seen = Vec::new();
    for extension in &parsed.extensions {
        let kind = extension.ext_type;
        // RFC 6066 section 3 excludes literal IP addresses from server_name.
        if kind == 0 && host.is_some_and(|name| name.parse::<IpAddr>().is_ok()) {
            continue;
        }
        if matches!(kind, 0x0015 | 0x0039) || seen.contains(&kind) {
            continue;
        }
        seen.push(kind);
        let data = match kind {
            0x0000 if host.is_some() => server_name(host?)?,
            0x0010 => b"\x00\x03\x02h3".to_vec(),
            0x002b => b"\x02\x03\x04".to_vec(),
            0x0033 => {
                let mut shares = extension.data.to_vec();
                randomize_key_shares(&mut shares, random)?;
                shares
            }
            _ => extension.data.to_vec(),
        };
        append_extension(&mut extensions, kind, &data)?;
    }
    // Initial source CID is mandatory; bind it to this probe's fresh header.
    let mut parameters = vec![0x0f];
    parameters.extend_from_slice(&encode_quic_varint(scid.len() as u64));
    parameters.extend_from_slice(scid);
    append_extension(&mut extensions, 0x0039, &parameters)?;
    append_u16_vector(&mut body, &extensions)?;

    let record_len = u16::try_from(body.len().checked_add(4)?).ok()?;
    let mut hello = vec![0x16, 0x03, 0x01];
    hello.extend_from_slice(&record_len.to_be_bytes());
    hello.push(0x01);
    hello.extend_from_slice(&(body.len() as u32).to_be_bytes()[1..]);
    hello.extend_from_slice(&body);
    Some(hello)
}

fn server_name(host: &str) -> Option<Vec<u8>> {
    if host.is_empty() {
        return None;
    }
    let mut name = vec![0];
    append_u16_vector(&mut name, host.as_bytes())?;
    let mut list = Vec::new();
    append_u16_vector(&mut list, &name)?;
    Some(list)
}

fn append_extension(out: &mut Vec<u8>, kind: u16, data: &[u8]) -> Option<()> {
    out.extend_from_slice(&kind.to_be_bytes());
    append_u16_vector(out, data)
}

fn append_u16_vector(out: &mut Vec<u8>, data: &[u8]) -> Option<()> {
    out.extend_from_slice(&u16::try_from(data.len()).ok()?.to_be_bytes());
    out.extend_from_slice(data);
    Some(())
}

fn randomize_key_shares(shares: &mut [u8], random: &SystemRandom) -> Option<()> {
    let end = read_u16(shares, 0)?.checked_add(2)?;
    if end != shares.len() {
        return None;
    }
    let mut cursor = 2;
    while cursor < end {
        let group = read_u16(shares, cursor)?;
        let key_len = read_u16(shares, cursor + 2)?;
        let key = shares.get_mut(cursor + 4..cursor + 4 + key_len)?;
        let algorithm = match (group, key_len) {
            (0x001d, 32) => Some(&agreement::X25519),
            (0x0017, 65) => Some(&agreement::ECDH_P256),
            _ => None,
        };
        match algorithm {
            Some(algorithm) => {
                let public =
                    agreement::EphemeralPrivateKey::generate(algorithm, random).ok()?.compute_public_key().ok()?;
                key.copy_from_slice(public.as_ref());
            }
            None => random.fill(key).ok()?,
        }
        cursor += 4 + key_len;
    }
    Some(())
}

#[cfg(test)]
mod tests;
