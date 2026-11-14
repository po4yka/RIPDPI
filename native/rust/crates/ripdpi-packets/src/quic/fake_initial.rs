use crate::tls::{TLS_RECORD_HEADER_LEN, change_tls_sni_seeded_like_c, is_tls_client_hello};
use crate::types::{QUIC_V1_VERSION, QuicInitialBrowserProfile, QuicInitialPacketLayout, QuicInitialSeed};
use crate::util::read_u16;
use crate::{TlsFakeProfile, tls_fake_profile_bytes};

use super::build::build_quic_initial_raw;
use super::datagram::decode_quic_initial_datagram;
use super::frames::{append_segmented_quic_crypto_frames, encode_quic_varint};
use super::parse::supported_quic_version;
use super::probe::probe_client_hello;
use super::{QUIC_FAKE_DCID, QUIC_FAKE_INITIAL_TARGET_LEN, QUIC_FAKE_SCID, QUIC_INITIAL_MIN_LEN, QUIC_MAX_CID_LEN};

fn padded_tls_client_hello(template: &[u8]) -> Vec<u8> {
    let mut client_hello = template.to_vec();
    let target_len = read_u16(template, 3).map_or(client_hello.len(), |record_len| record_len + TLS_RECORD_HEADER_LEN);
    if client_hello.len() < target_len {
        client_hello.resize(target_len, 0);
    }
    client_hello
}

fn tls_record_from_handshake(handshake: &[u8]) -> Option<Vec<u8>> {
    if handshake.len() > u16::MAX as usize || handshake.first().copied()? != 0x01 {
        return None;
    }
    let mut record = Vec::with_capacity(TLS_RECORD_HEADER_LEN + handshake.len());
    record.extend_from_slice(&[0x16, 0x03, 0x01]);
    record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    record.extend_from_slice(handshake);
    Some(record)
}

fn tls_profile_for_quic_browser(profile: QuicInitialBrowserProfile) -> TlsFakeProfile {
    match profile {
        QuicInitialBrowserProfile::ChromeAndroid => TlsFakeProfile::GoogleChrome,
        QuicInitialBrowserProfile::FirefoxAndroid => TlsFakeProfile::IanaFirefox,
    }
}

const EXT_ALPN: usize = 0x0010;
const EXT_PADDING: usize = 0x0015;
const EXT_SUPPORTED_VERSIONS: usize = 0x002b;
const EXT_QUIC_TRANSPORT_PARAMETERS: usize = 0x0039;
/// TCP-only ALPS (old and new codepoints) advertises `h2` settings and contradicts the QUIC `h3` ALPN.
const EXT_ALPS_OLD: usize = 0x4469;
const EXT_ALPS: usize = 0x44cd;

fn quic_transport_parameters(scid: &[u8], profile: TlsFakeProfile) -> Vec<u8> {
    let mut params: Vec<(u64, Vec<u8>)> = if profile == TlsFakeProfile::IanaFirefox {
        [
            (0x04, 25_165_824),
            (0x05, 12_582_912),
            (0x06, 1_048_576),
            (0x07, 1_048_576),
            (0x08, 16),
            (0x09, 16),
            (0x01, 30_000),
            (0x0e, 8),
        ]
        .map(|(id, value)| (id, encode_quic_varint(value)))
        .into()
    } else {
        [
            (0x01, 30_000),
            (0x03, 1472),
            (0x04, 15_728_640),
            (0x05, 6_291_456),
            (0x06, 6_291_456),
            (0x07, 6_291_456),
            (0x08, 100),
            (0x09, 103),
            (0x0e, 4),
        ]
        .map(|(id, value)| (id, encode_quic_varint(value)))
        .into()
    };
    params.push((0x0f, scid.to_vec()));
    let mut out = Vec::new();
    for (id, value) in params {
        out.extend_from_slice(&encode_quic_varint(id));
        out.extend_from_slice(&encode_quic_varint(value.len() as u64));
        out.extend_from_slice(&value);
    }
    out
}

fn push_extension(out: &mut Vec<u8>, id: usize, data: &[u8]) -> Option<()> {
    out.extend_from_slice(&u16::try_from(id).ok()?.to_be_bytes());
    out.extend_from_slice(&u16::try_from(data.len()).ok()?.to_be_bytes());
    out.extend_from_slice(data);
    Some(())
}

/// Rewrites a TCP ClientHello record into a QUIC ClientHello (RFC 9001 4.2, 8.1, 8.2, 8.4): empty legacy session
/// id, TLS 1.3 only, ALPN `h3`, a `quic_transport_parameters` extension, and no TCP-only padding or ALPS.
/// Extension order of the template is kept; replaced extensions stay in place, missing ones are appended.
fn quic_client_hello_from_tcp_template(record: &[u8], scid: &[u8], profile: TlsFakeProfile) -> Option<Vec<u8>> {
    let hs_len = read_u16(record, 7)? + (usize::from(*record.get(6)?) << 16);
    let body = record.get(TLS_RECORD_HEADER_LEN + 4..TLS_RECORD_HEADER_LEN + 4 + hs_len)?;
    let sid_len = usize::from(*body.get(34)?);
    let cs_start = 35 + sid_len;
    let mut pos = cs_start;
    let cs_len = read_u16(body, pos)?;
    let cs_end = pos + 2 + cs_len;
    let comp_len = usize::from(*body.get(cs_end)?);
    pos = cs_end + 1 + comp_len;
    let ext_len = read_u16(body, pos)?;
    let mut exts = body.get(pos + 2..pos + 2 + ext_len)?;

    let mut new_exts = Vec::new();
    let (mut alpn, mut versions) = (false, false);
    while !exts.is_empty() {
        let id = read_u16(exts, 0)?;
        let len = read_u16(exts, 2)?;
        let data = exts.get(4..4 + len)?;
        exts = &exts[4 + len..];
        match id {
            EXT_ALPN => {
                alpn = true;
                push_extension(&mut new_exts, id, &[0, 3, 2, b'h', b'3'])?;
            }
            EXT_SUPPORTED_VERSIONS => {
                versions = true;
                push_extension(&mut new_exts, id, &[2, 0x03, 0x04])?;
            }
            EXT_PADDING | EXT_QUIC_TRANSPORT_PARAMETERS | EXT_ALPS | EXT_ALPS_OLD => {}
            _ => push_extension(&mut new_exts, id, data)?,
        }
    }
    if !alpn {
        push_extension(&mut new_exts, EXT_ALPN, &[0, 3, 2, b'h', b'3'])?;
    }
    if !versions {
        push_extension(&mut new_exts, EXT_SUPPORTED_VERSIONS, &[2, 0x03, 0x04])?;
    }
    push_extension(&mut new_exts, EXT_QUIC_TRANSPORT_PARAMETERS, &quic_transport_parameters(scid, profile))?;

    let mut hello = Vec::new();
    hello.extend_from_slice(&body[..32 + 2]);
    hello.push(0);
    hello.extend_from_slice(&body[cs_start..pos]);
    hello.extend_from_slice(&u16::try_from(new_exts.len()).ok()?.to_be_bytes());
    hello.extend_from_slice(&new_exts);

    let mut handshake = vec![0x01];
    handshake.extend_from_slice(&u32::try_from(hello.len()).ok()?.to_be_bytes()[1..]);
    handshake.extend_from_slice(&hello);
    tls_record_from_handshake(&handshake)
}

pub fn build_browser_like_quic_initial_seed(
    version: u32,
    host_override: Option<&str>,
    profile: QuicInitialBrowserProfile,
) -> Option<QuicInitialSeed> {
    build_quic_initial_seed_for_tls_profile(version, host_override, tls_profile_for_quic_browser(profile))
}

/// Build a synthetic QUIC ClientHello with caller-owned connection IDs.
///
/// The ClientHello uses fresh TLS random and key shares, h3 ALPN, and QUIC
/// transport parameters bound to `scid`. The profile selects a browser-shaped
/// TLS extension order; it does not assert parity with a browser version.
/// Returns `None` for an unsupported version, a DCID outside 8..=20 bytes,
/// an SCID above 20 bytes, or a ClientHello generation failure.
pub fn build_browser_like_quic_initial_seed_with_cids(
    version: u32,
    host_override: Option<&str>,
    profile: QuicInitialBrowserProfile,
    dcid: &[u8],
    scid: &[u8],
) -> Option<QuicInitialSeed> {
    // RFC 9000 section 7.2 requires at least eight bytes in the first client DCID.
    if !supported_quic_version(version)
        || !(8..=QUIC_MAX_CID_LEN).contains(&dcid.len())
        || scid.len() > QUIC_MAX_CID_LEN
    {
        return None;
    }
    let tls_profile = tls_profile_for_quic_browser(profile);
    let client_hello =
        probe_client_hello(tls_fake_profile_bytes(tls_profile), host_override, scid, &ring::rand::SystemRandom::new())?;
    Some(QuicInitialSeed { version, dcid: dcid.to_vec(), scid: scid.to_vec(), token: Vec::new(), client_hello })
}

fn build_quic_initial_seed_for_tls_profile(
    version: u32,
    host_override: Option<&str>,
    profile: TlsFakeProfile,
) -> Option<QuicInitialSeed> {
    let mut client_hello = padded_tls_client_hello(tls_fake_profile_bytes(profile));
    if let Some(host) = host_override {
        let capacity = client_hello.len().saturating_add(host.len()).saturating_add(64);
        let mutation = change_tls_sni_seeded_like_c(&client_hello, host.as_bytes(), capacity, 7);
        if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
            client_hello = mutation.bytes;
        }
    }
    // The transport parameters carry initial_source_connection_id, which RFC 9000 7.3 requires to equal the
    // Initial header SCID. Both use QUIC_FAKE_SCID here; a caller that changes `seed.scid` must rebuild the hello.
    let client_hello = quic_client_hello_from_tcp_template(&client_hello, &QUIC_FAKE_SCID, profile)?;
    Some(QuicInitialSeed {
        version: if supported_quic_version(version) { version } else { QUIC_V1_VERSION },
        dcid: QUIC_FAKE_DCID.to_vec(),
        scid: QUIC_FAKE_SCID.to_vec(),
        token: Vec::new(),
        client_hello,
    })
}

pub fn packetize_quic_initial(seed: &QuicInitialSeed, layout: &QuicInitialPacketLayout) -> Option<Vec<u8>> {
    if !is_tls_client_hello(&seed.client_hello) {
        return None;
    }
    let mut plaintext = Vec::new();
    append_segmented_quic_crypto_frames(
        &mut plaintext,
        &seed.client_hello[TLS_RECORD_HEADER_LEN..],
        &layout.crypto_frame_offsets,
    )?;
    plaintext.extend(std::iter::repeat_n(0u8, layout.extra_tail_padding));
    build_quic_initial_raw(
        seed.version,
        &seed.dcid,
        &seed.scid,
        &seed.token,
        plaintext,
        layout.min_datagram_len.max(QUIC_INITIAL_MIN_LEN),
        layout.packet_number,
    )
}

pub fn parse_quic_initial_seed(packet: &[u8]) -> Option<QuicInitialSeed> {
    let decoded = decode_quic_initial_datagram(packet)?;
    let header = decoded.header;
    Some(QuicInitialSeed {
        version: header.version,
        dcid: header.dcid.to_vec(),
        scid: header.scid.to_vec(),
        token: header.token.to_vec(),
        client_hello: tls_record_from_handshake(&decoded.client_hello)?,
    })
}

pub fn build_browser_like_quic_initial(
    version: u32,
    host_override: Option<&str>,
    profile: QuicInitialBrowserProfile,
) -> Option<Vec<u8>> {
    let seed = build_browser_like_quic_initial_seed(version, host_override, profile)?;
    packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(QUIC_FAKE_INITIAL_TARGET_LEN))
}

pub fn build_generic_quic_initial(version: u32, host_override: Option<&str>) -> Option<Vec<u8>> {
    let seed = build_quic_initial_seed_for_tls_profile(version, host_override, TlsFakeProfile::CompatDefault)?;
    packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(QUIC_FAKE_INITIAL_TARGET_LEN))
}

pub fn build_realistic_quic_initial(version: u32, host_override: Option<&str>) -> Option<Vec<u8>> {
    build_browser_like_quic_initial(version, host_override, QuicInitialBrowserProfile::ChromeAndroid)
}
