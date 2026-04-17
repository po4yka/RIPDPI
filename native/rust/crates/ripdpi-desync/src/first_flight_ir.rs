use std::ops::Range;

use ripdpi_packets::{
    parse_quic_initial_layout, parse_tls_client_hello_handshake_layout, parse_tls_client_hello_layout,
    QuicCryptoFrameInfo, QuicInitialLayout, TlsClientHelloLayout, TlsExtensionInfo,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesiredBoundaryPlan {
    pub tcp_segment_boundaries: Vec<usize>,
    pub udp_datagram_boundaries: Vec<usize>,
    pub crypto_frame_boundaries: Vec<usize>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct GreaseProfile {
    pub cipher_suites: Vec<u16>,
    pub extensions: Vec<u16>,
    pub supported_groups: Vec<u16>,
    pub supported_versions: Vec<u16>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlsRecordBoundary {
    pub header: Range<usize>,
    pub payload: Range<usize>,
    pub handshake: Range<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlsExtensionBoundary {
    pub ext_type: u16,
    pub type_range: Range<usize>,
    pub data_range: Range<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlsClientHelloIr {
    pub raw: Vec<u8>,
    pub authority: Vec<u8>,
    pub authority_span: Range<usize>,
    pub alpn_protocols: Vec<Vec<u8>>,
    pub has_ech: bool,
    pub grease: GreaseProfile,
    pub record_boundaries: Vec<TlsRecordBoundary>,
    pub extensions: Vec<TlsExtensionBoundary>,
    pub desired: DesiredBoundaryPlan,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QuicCryptoFrameBoundary {
    pub crypto_range: Range<usize>,
    pub packet_payload_range: Range<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QuicInitialIr {
    pub version: u32,
    pub tls_client_hello: TlsClientHelloIr,
    pub crypto_frames: Vec<QuicCryptoFrameBoundary>,
    pub desired: DesiredBoundaryPlan,
}

fn read_u16_at(buffer: &[u8], offset: usize) -> Option<u16> {
    let bytes = buffer.get(offset..offset + 2)?;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn is_grease_value(value: u16) -> bool {
    let [hi, lo] = value.to_be_bytes();
    hi == lo && (hi & 0x0f) == 0x0a
}

fn parse_cipher_suites(client_hello: &[u8], handshake_start: usize) -> Option<Vec<u16>> {
    let client_hello = client_hello.get(handshake_start..)?;
    if client_hello.len() < 42 || client_hello.first().copied()? != 0x01 {
        return None;
    }
    let mut cursor = 4usize;
    cursor += 2 + 32;
    let session_id_len = usize::from(*client_hello.get(cursor)?);
    cursor += 1 + session_id_len;
    let cipher_len = usize::from(read_u16_at(client_hello, cursor)?);
    cursor += 2;
    let cipher_bytes = client_hello.get(cursor..cursor + cipher_len)?;
    if cipher_bytes.len() % 2 != 0 {
        return None;
    }
    Some(cipher_bytes.chunks_exact(2).map(|chunk| u16::from_be_bytes([chunk[0], chunk[1]])).collect())
}

fn extension_data<'a>(buffer: &'a [u8], extension: &TlsExtensionInfo) -> Option<&'a [u8]> {
    buffer.get(extension.data_offset..extension.data_offset + extension.data_len)
}

fn parse_alpn_protocols(buffer: &[u8], extensions: &[TlsExtensionInfo]) -> Vec<Vec<u8>> {
    let Some(alpn) = extensions.iter().find(|extension| extension.ext_type == 0x0010) else {
        return Vec::new();
    };
    let Some(data) = extension_data(buffer, alpn) else {
        return Vec::new();
    };
    if data.len() < 2 {
        return Vec::new();
    }
    let mut cursor = 2usize;
    let mut protocols = Vec::new();
    while cursor < data.len() {
        let len = usize::from(*data.get(cursor).unwrap_or(&0));
        cursor += 1;
        let Some(protocol) = data.get(cursor..cursor + len) else {
            return Vec::new();
        };
        protocols.push(protocol.to_vec());
        cursor += len;
    }
    protocols
}

fn parse_supported_groups(buffer: &[u8], extensions: &[TlsExtensionInfo]) -> Vec<u16> {
    let Some(groups) = extensions.iter().find(|extension| extension.ext_type == 0x000a) else {
        return Vec::new();
    };
    let Some(data) = extension_data(buffer, groups) else {
        return Vec::new();
    };
    if data.len() < 2 {
        return Vec::new();
    }
    data[2..].chunks_exact(2).map(|chunk| u16::from_be_bytes([chunk[0], chunk[1]])).collect()
}

fn parse_supported_versions(buffer: &[u8], extensions: &[TlsExtensionInfo]) -> Vec<u16> {
    let Some(versions) = extensions.iter().find(|extension| extension.ext_type == 0x002b) else {
        return Vec::new();
    };
    let Some(data) = extension_data(buffer, versions) else {
        return Vec::new();
    };
    if data.is_empty() {
        return Vec::new();
    }
    data[1..].chunks_exact(2).map(|chunk| u16::from_be_bytes([chunk[0], chunk[1]])).collect()
}

fn normalize_tls_from_layout(
    raw: &[u8],
    layout: TlsClientHelloLayout,
    record_boundaries: Vec<TlsRecordBoundary>,
) -> Option<TlsClientHelloIr> {
    let authority = raw.get(layout.markers.host_start..layout.markers.host_end)?.to_vec();
    let authority_span = layout.markers.host_start..layout.markers.host_end;
    let handshake_start = usize::from(layout.record_payload_len > 0) * 5;
    let cipher_suites = parse_cipher_suites(raw, handshake_start)?;
    let supported_groups = parse_supported_groups(raw, &layout.extensions);
    let supported_versions = parse_supported_versions(raw, &layout.extensions);
    let grease = GreaseProfile {
        cipher_suites: cipher_suites.into_iter().filter(|value| is_grease_value(*value)).collect(),
        extensions: layout
            .extensions
            .iter()
            .map(|extension| extension.ext_type)
            .filter(|value| is_grease_value(*value))
            .collect(),
        supported_groups: supported_groups.into_iter().filter(|value| is_grease_value(*value)).collect(),
        supported_versions: supported_versions.into_iter().filter(|value| is_grease_value(*value)).collect(),
    };
    let extensions = layout
        .extensions
        .iter()
        .map(|extension| TlsExtensionBoundary {
            ext_type: extension.ext_type,
            type_range: extension.type_offset..extension.type_offset + 2,
            data_range: extension.data_offset..extension.data_offset + extension.data_len,
        })
        .collect::<Vec<_>>();
    let desired = DesiredBoundaryPlan {
        tcp_segment_boundaries: record_boundaries.iter().map(|boundary| boundary.payload.end).collect(),
        udp_datagram_boundaries: Vec::new(),
        crypto_frame_boundaries: Vec::new(),
    };
    Some(TlsClientHelloIr {
        raw: raw.to_vec(),
        authority,
        authority_span,
        alpn_protocols: parse_alpn_protocols(raw, &layout.extensions),
        has_ech: layout.markers.ech_ext_start.is_some(),
        grease,
        record_boundaries,
        extensions,
        desired,
    })
}

pub fn normalize_tls_client_hello(packet: &[u8]) -> Option<TlsClientHelloIr> {
    let layout = parse_tls_client_hello_layout(packet)?;
    let payload_end = 5usize.checked_add(layout.record_payload_len)?.min(packet.len());
    let handshake_end = 5usize.checked_add(4)?.checked_add(layout.handshake_payload_len)?.min(packet.len());
    let record_boundaries =
        vec![TlsRecordBoundary { header: 0..5, payload: 5..payload_end, handshake: 5..handshake_end }];
    normalize_tls_from_layout(packet, layout, record_boundaries)
}

fn normalize_tls_client_hello_handshake(handshake: &[u8]) -> Option<TlsClientHelloIr> {
    let layout = parse_tls_client_hello_handshake_layout(handshake)?;
    normalize_tls_from_layout(handshake, layout, Vec::new())
}

fn map_quic_crypto_frames(frames: &[QuicCryptoFrameInfo]) -> Vec<QuicCryptoFrameBoundary> {
    frames
        .iter()
        .map(|frame| QuicCryptoFrameBoundary {
            crypto_range: frame.crypto_offset..frame.crypto_offset + frame.data_len,
            packet_payload_range: frame.data_offset..frame.data_offset + frame.data_len,
        })
        .collect()
}

pub fn normalize_quic_initial(packet: &[u8]) -> Option<QuicInitialIr> {
    let layout: QuicInitialLayout = parse_quic_initial_layout(packet)?;
    let mut tls_client_hello = normalize_tls_client_hello_handshake(&layout.info.client_hello)?;
    let crypto_frames = map_quic_crypto_frames(&layout.crypto_frames);
    let desired = DesiredBoundaryPlan {
        tcp_segment_boundaries: Vec::new(),
        udp_datagram_boundaries: vec![packet.len()],
        crypto_frame_boundaries: crypto_frames.iter().map(|frame| frame.crypto_range.end).collect(),
    };
    tls_client_hello.desired.crypto_frame_boundaries = desired.crypto_frame_boundaries.clone();
    Some(QuicInitialIr { version: layout.info.version, tls_client_hello, crypto_frames, desired })
}
