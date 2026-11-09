use std::ops::Range;

use crate::types::TlsMarkerInfo;

/// SNI and byte positions in the original stream for a fragmented ClientHello.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReassembledTlsSni {
    pub host: Vec<u8>,
    /// Original stream offset for each byte of `host`.
    pub host_offsets: Vec<usize>,
    pub ext_len_start: usize,
    pub sni_ext_start: usize,
    /// Present only when the host is contiguous in the original stream.
    pub contiguous_markers: Option<TlsMarkerInfo>,
}

/// Extract an SNI spanning complete handshake records without changing the stream.
///
/// Returns `None` unless the ClientHello is fragmented over two or more handshake
/// records and every handshake byte is present.
pub fn reassembled_tls_sni(buffer: &[u8]) -> Option<ReassembledTlsSni> {
    if buffer.first() != Some(&0x16) || buffer.get(1) != Some(&0x03) || buffer.get(2).is_none_or(|&minor| minor > 4) {
        return None;
    }
    let layout = super::records::parse_client_hello_layout(buffer)?;
    let spans = &layout.record_payload_spans;
    let handshake_len = 4 + layout.handshake_payload_len;
    let present: usize = spans.iter().map(ExactSizeIterator::len).sum();
    if spans.len() < 2
        || layout.record_payload_len >= handshake_len
        || spans.iter().any(Range::is_empty)
        || present < handshake_len
    {
        return None;
    }
    let host_offsets: Vec<usize> = layout.host_spans.iter().flat_map(Clone::clone).collect();
    let host = host_offsets.iter().map(|&offset| buffer[offset]).collect();
    let contiguous_markers = (layout.host_spans.len() == 1).then_some(layout.markers);
    Some(ReassembledTlsSni {
        host,
        host_offsets,
        ext_len_start: layout.markers.ext_len_start,
        sni_ext_start: layout.markers.sni_ext_start,
        contiguous_markers,
    })
}
