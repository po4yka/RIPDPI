//! ClientHello parsing across TLS records.
//!
//! A ClientHello may be split over several consecutive handshake records. This
//! module flattens the record payloads, parses the flattened handshake with
//! `tls_nom`, and maps every offset back to the original buffer so that no
//! reported offset points at a record header.

use std::borrow::Cow;
use std::ops::Range;

use crate::tls_nom::{parse_client_hello_handshake, to_marker_info};
use crate::types::{TlsClientHelloLayout, TlsExtensionInfo, TlsMarkerInfo};
use crate::util::{read_u16, read_u24};

struct RecordPayloads<'a> {
    spans: Vec<Range<usize>>,
    flat: Cow<'a, [u8]>,
}

impl<'a> RecordPayloads<'a> {
    /// Collect the payloads of the consecutive records that share the first
    /// record's type and version. The last record may be cut by the buffer.
    fn collect(buffer: &'a [u8]) -> Option<Self> {
        let header = buffer.get(..3)?;
        let mut spans = Vec::new();
        let mut cursor = 0usize;
        while buffer.get(cursor..cursor + 3) == Some(header) && cursor + 5 <= buffer.len() {
            let start = cursor + 5;
            let end = (start + read_u16(buffer, cursor + 3)?).min(buffer.len());
            spans.push(start..end);
            cursor = end;
        }
        let flat = match spans.as_slice() {
            [] => return None,
            [only] => Cow::Borrowed(&buffer[only.clone()]),
            _ => Cow::Owned(spans.iter().flat_map(|span| buffer[span.clone()].iter().copied()).collect()),
        };
        Some(Self { spans, flat })
    }

    /// Original offset of flattened offset `flat`, and the bytes left in its record.
    /// A record boundary maps to the start of the next record.
    fn map(&self, flat: usize) -> Option<(usize, usize)> {
        let mut cursor = 0usize;
        for span in &self.spans {
            let len = span.end - span.start;
            if flat < cursor + len {
                return Some((span.start + flat - cursor, len - (flat - cursor)));
            }
            cursor += len;
        }
        (flat == cursor).then(|| (self.spans.last().map_or(0, |span| span.end), 0))
    }

    /// Original ranges covering flattened range `flat`, one per record it touches.
    fn spans_of(&self, flat: Range<usize>) -> Option<Vec<Range<usize>>> {
        let mut cursor = 0usize;
        let mut out = Vec::new();
        for span in &self.spans {
            let len = span.end - span.start;
            let (from, to) = (flat.start.max(cursor), flat.end.min(cursor + len));
            if from < to {
                out.push(span.start + from - cursor..span.start + to - cursor);
            }
            cursor += len;
        }
        (out.iter().map(|span| span.end - span.start).sum::<usize>() == flat.len()).then_some(out)
    }
}

/// Parse the ClientHello starting at `buffer[0]`. All offsets and spans refer
/// to `buffer`. `host_spans` has more than one entry when the host name
/// crosses a record boundary.
pub(super) fn parse_client_hello_layout(buffer: &[u8]) -> Option<TlsClientHelloLayout> {
    let payloads = RecordPayloads::collect(buffer)?;
    let parsed = parse_client_hello_handshake(&payloads.flat)?;
    let flat_markers = to_marker_info(&parsed, payloads.flat.len())?;
    let host_spans = payloads.spans_of(flat_markers.host_start..flat_markers.host_end)?;
    let markers = TlsMarkerInfo {
        ext_len_start: payloads.map(flat_markers.ext_len_start)?.0,
        ech_ext_start: match flat_markers.ech_ext_start {
            Some(flat) => Some(payloads.map(flat)?.0),
            None => None,
        },
        sni_ext_start: payloads.map(flat_markers.sni_ext_start)?.0,
        host_start: host_spans.first()?.start,
        host_end: host_spans.last()?.end,
    };
    let extensions = parsed
        .extensions
        .iter()
        .map(|ext| {
            let (type_offset, _) = payloads.map(ext.type_offset)?;
            let (data_offset, room) = payloads.map(ext.type_offset + 4)?;
            // Extension data cut by a record boundary is reported up to that boundary.
            Some(TlsExtensionInfo {
                ext_type: ext.ext_type,
                type_offset,
                data_offset,
                data_len: ext.data.len().min(room),
            })
        })
        .collect::<Option<Vec<_>>>()?;
    Some(TlsClientHelloLayout {
        markers,
        record_payload_len: read_u16(buffer, 3)?,
        handshake_payload_len: read_u24(&payloads.flat, 1)?,
        extensions,
        record_payload_spans: payloads.spans,
        host_spans,
    })
}
