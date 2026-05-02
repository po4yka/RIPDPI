use std::ops::Range;

mod extension_readers;
mod handshake_layout;
mod offset_mapping;
mod record_framing;

use extension_readers::{parse_server_name_hostname, validate_alpn_extension};
use handshake_layout::{parse_client_hello_layout_in_handshake, parse_client_hello_layout_in_record};
use offset_mapping::map_flattened_layout;
use record_framing::collect_tls_record_payload_spans;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientHelloOffsets {
    pub handshake_start: usize,
    pub extensions_start: usize,
    pub extensions_end: usize,
    pub server_name_extension_start: Option<usize>,
    pub sni_hostname: Option<Range<usize>>,
    pub alpn_extension_start: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientHelloOffsetsError {
    InvalidRecordHeader,
    TruncatedRecord,
    InvalidHandshakeType,
    InvalidHandshakeLength,
    InvalidClientHelloLayout,
    InvalidExtensionListLength,
    InvalidServerNameExtension,
    InvalidAlpnExtension,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedExtension {
    pub(crate) ext_type: u16,
    pub(crate) type_offset: usize,
    pub(crate) data_offset: usize,
    pub(crate) data_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedClientHelloLayout {
    pub(crate) raw: Vec<u8>,
    pub(crate) payload_spans: Vec<Range<usize>>,
    pub(crate) handshake_start: usize,
    pub(crate) extensions_start: usize,
    pub(crate) extensions_end: usize,
    pub(crate) extensions: Vec<ParsedExtension>,
}

pub fn parse_client_hello_offsets(buffer: &[u8]) -> Result<ClientHelloOffsets, ClientHelloOffsetsError> {
    let payload_spans = collect_tls_record_payload_spans(buffer)?;
    let layout = if payload_spans.len() == 1 {
        parse_client_hello_layout_in_record(buffer)?
    } else {
        let flattened_len = payload_spans.iter().map(|span| span.end.saturating_sub(span.start)).sum::<usize>();
        let mut flattened = Vec::with_capacity(flattened_len);
        for span in &payload_spans {
            flattened.extend_from_slice(&buffer[span.start..span.end]);
        }
        let flattened_layout = parse_client_hello_layout_in_handshake(&flattened)?;
        map_flattened_layout(buffer, &payload_spans, flattened_layout)?
    };
    build_offsets(layout)
}

fn build_offsets(layout: ParsedClientHelloLayout) -> Result<ClientHelloOffsets, ClientHelloOffsetsError> {
    let server_name_extension = layout.extensions.iter().find(|extension| extension.ext_type == 0x0000);
    let alpn_extension = layout.extensions.iter().find(|extension| extension.ext_type == 0x0010);
    let sni_hostname = parse_server_name_hostname(&layout, server_name_extension)?;
    validate_alpn_extension(&layout, alpn_extension)?;

    Ok(ClientHelloOffsets {
        handshake_start: layout.handshake_start,
        extensions_start: layout.extensions_start,
        extensions_end: layout.extensions_end,
        server_name_extension_start: server_name_extension.map(|extension| extension.type_offset),
        sni_hostname,
        alpn_extension_start: alpn_extension.map(|extension| extension.type_offset),
    })
}

pub(crate) fn read_u16(buffer: &[u8], offset: usize) -> Option<usize> {
    let bytes = buffer.get(offset..offset + 2)?;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]) as usize)
}

pub(crate) fn read_u24(buffer: &[u8], offset: usize) -> Option<usize> {
    let bytes = buffer.get(offset..offset + 3)?;
    Some(((bytes[0] as usize) << 16) | ((bytes[1] as usize) << 8) | bytes[2] as usize)
}
