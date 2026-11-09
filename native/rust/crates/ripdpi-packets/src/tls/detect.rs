use crate::types::{TlsClientHelloLayout, TlsExtensionInfo, TlsMarkerInfo};
use crate::util::{read_u16, read_u24};

use super::edit::find_tls_ext_offset;

pub fn is_tls_client_hello(buffer: &[u8]) -> bool {
    buffer.len() > 5 && read_u16(buffer, 0) == Some(0x1603) && buffer[5] == 0x01
}

pub fn is_tls_server_hello(buffer: &[u8]) -> bool {
    buffer.len() > 5 && read_u16(buffer, 0) == Some(0x1603) && buffer[5] == 0x02
}

pub(crate) fn tls_client_hello_marker_info_in_handshake(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    let parsed = crate::tls_nom::parse_client_hello_handshake(buffer)?;
    crate::tls_nom::to_marker_info(&parsed, buffer.len())
}

fn tls_client_hello_marker_info_in_record(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    if !is_tls_client_hello(buffer) {
        return None;
    }
    let layout = super::records::parse_client_hello_layout(buffer)?;
    (layout.host_spans.len() == 1).then_some(layout.markers)
}

pub fn parse_tls(buffer: &[u8]) -> Option<&[u8]> {
    let markers = tls_client_hello_marker_info_in_record(buffer)?;
    Some(&buffer[markers.host_start..markers.host_end])
}

pub fn tls_marker_info(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    tls_client_hello_marker_info_in_record(buffer)
}

pub fn parse_tls_client_hello_layout(buffer: &[u8]) -> Option<TlsClientHelloLayout> {
    super::records::parse_client_hello_layout(buffer)
}

pub fn parse_tls_client_hello_handshake_layout(buffer: &[u8]) -> Option<TlsClientHelloLayout> {
    let parsed = crate::tls_nom::parse_client_hello_handshake(buffer)?;
    let markers = crate::tls_nom::to_marker_info(&parsed, buffer.len())?;
    let handshake_payload_len = read_u24(buffer, 1)?;
    let extensions = parsed
        .extensions
        .iter()
        .map(|ext| TlsExtensionInfo {
            ext_type: ext.ext_type,
            type_offset: ext.type_offset,
            data_offset: ext.type_offset + 4,
            data_len: ext.data.len(),
        })
        .collect();
    #[allow(clippy::single_range_in_vec_init)]
    let host_spans = vec![markers.host_start..markers.host_end];
    Some(TlsClientHelloLayout {
        markers,
        record_payload_len: 0,
        handshake_payload_len,
        extensions,
        record_payload_spans: Vec::new(),
        host_spans,
    })
}

pub fn tls_session_id_mismatch(req: &[u8], resp: &[u8]) -> bool {
    if req.len() < 75 || resp.len() < 75 {
        return false;
    }
    if !is_tls_client_hello(req) || read_u16(resp, 0) != Some(0x1603) {
        return false;
    }
    let sid_len = req[43] as usize;
    if 44 + sid_len > req.len() || 44 + sid_len > resp.len() {
        return false;
    }
    let skip = 44 + sid_len + 3;
    if find_tls_ext_offset(0x002b, resp, skip).is_none() {
        return false;
    }
    if req[43] != resp[43] {
        return true;
    }
    req.get(44..44 + sid_len) != resp.get(44..44 + sid_len)
}
