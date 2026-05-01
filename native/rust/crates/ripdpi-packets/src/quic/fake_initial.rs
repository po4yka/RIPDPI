use crate::tls::{change_tls_sni_seeded_like_c, is_tls_client_hello, TLS_RECORD_HEADER_LEN};
use crate::types::{QuicInitialBrowserProfile, QuicInitialPacketLayout, QuicInitialSeed, QUIC_V1_VERSION};
use crate::util::read_u16;
use crate::{tls_fake_profile_bytes, TlsFakeProfile};

use super::build::build_quic_initial_raw;
use super::frames::{append_segmented_quic_crypto_frames, defrag_quic_crypto_frames};
use super::parse::{decrypt_quic_initial_payload, parse_quic_initial_header, supported_quic_version};
use super::{QUIC_FAKE_DCID, QUIC_FAKE_INITIAL_TARGET_LEN, QUIC_FAKE_SCID, QUIC_INITIAL_MIN_LEN};

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

pub fn build_browser_like_quic_initial_seed(
    version: u32,
    host_override: Option<&str>,
    profile: QuicInitialBrowserProfile,
) -> Option<QuicInitialSeed> {
    let mut client_hello = padded_tls_client_hello(tls_fake_profile_bytes(tls_profile_for_quic_browser(profile)));
    if let Some(host) = host_override {
        let capacity = client_hello.len().saturating_add(host.len()).saturating_add(64);
        let mutation = change_tls_sni_seeded_like_c(&client_hello, host.as_bytes(), capacity, 7);
        if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
            client_hello = mutation.bytes;
        }
    }
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
    let header = parse_quic_initial_header(packet)?;
    let payload = decrypt_quic_initial_payload(packet, header)?;
    let (client_hello, is_complete) = defrag_quic_crypto_frames(&payload)?;
    if !is_complete {
        return None;
    }
    Some(QuicInitialSeed {
        version: header.version,
        dcid: header.dcid.to_vec(),
        scid: header.scid.to_vec(),
        token: header.token.to_vec(),
        client_hello: tls_record_from_handshake(&client_hello)?,
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

pub fn build_realistic_quic_initial(version: u32, host_override: Option<&str>) -> Option<Vec<u8>> {
    build_browser_like_quic_initial(version, host_override, QuicInitialBrowserProfile::ChromeAndroid)
}
