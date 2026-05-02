use std::ops::Range;

use super::{ClientHelloOffsetsError, ParsedClientHelloLayout, ParsedExtension};

pub(crate) fn map_flattened_layout(
    raw: &[u8],
    payload_spans: &[Range<usize>],
    layout: ParsedClientHelloLayout,
) -> Result<ParsedClientHelloLayout, ClientHelloOffsetsError> {
    let extensions = layout
        .extensions
        .into_iter()
        .map(|extension| {
            Ok(ParsedExtension {
                ext_type: extension.ext_type,
                type_offset: map_flattened_offset(payload_spans, extension.type_offset)?,
                data_offset: map_flattened_offset(payload_spans, extension.data_offset)?,
                data_end: map_flattened_offset(payload_spans, extension.data_end)?,
            })
        })
        .collect::<Result<Vec<_>, ClientHelloOffsetsError>>()?;

    Ok(ParsedClientHelloLayout {
        raw: raw.to_vec(),
        payload_spans: payload_spans.to_vec(),
        handshake_start: map_flattened_offset(payload_spans, layout.handshake_start)?,
        extensions_start: map_flattened_offset(payload_spans, layout.extensions_start)?,
        extensions_end: map_flattened_offset(payload_spans, layout.extensions_end)?,
        extensions,
    })
}

pub(crate) fn map_flattened_offset(
    payload_spans: &[Range<usize>],
    offset: usize,
) -> Result<usize, ClientHelloOffsetsError> {
    let total_len = payload_spans.iter().map(|span| span.end.saturating_sub(span.start)).sum::<usize>();
    if offset > total_len {
        return Err(ClientHelloOffsetsError::InvalidHandshakeLength);
    }
    if offset == total_len {
        return payload_spans.last().map(|span| span.end).ok_or(ClientHelloOffsetsError::InvalidHandshakeLength);
    }

    let mut cursor = 0usize;
    for span in payload_spans {
        let span_len = span.end.saturating_sub(span.start);
        if offset < cursor + span_len {
            return Ok(span.start + (offset - cursor));
        }
        cursor += span_len;
    }
    Err(ClientHelloOffsetsError::InvalidHandshakeLength)
}

pub(crate) fn map_extension_relative_offset(
    layout: &ParsedClientHelloLayout,
    extension: &ParsedExtension,
    relative_offset: usize,
) -> Option<usize> {
    if relative_offset > extension_payload_len(layout, extension) {
        return None;
    }

    let mut cursor = 0usize;
    for span in &layout.payload_spans {
        let overlap_start = span.start.max(extension.data_offset);
        let overlap_end = span.end.min(extension.data_end);
        if overlap_start >= overlap_end {
            continue;
        }
        let overlap_len = overlap_end - overlap_start;
        if relative_offset < cursor + overlap_len {
            return Some(overlap_start + (relative_offset - cursor));
        }
        cursor += overlap_len;
    }

    (relative_offset == cursor).then_some(extension.data_end)
}

pub(crate) fn extension_payload_len(layout: &ParsedClientHelloLayout, extension: &ParsedExtension) -> usize {
    layout
        .payload_spans
        .iter()
        .map(|span| {
            let overlap_start = span.start.max(extension.data_offset);
            let overlap_end = span.end.min(extension.data_end);
            overlap_end.saturating_sub(overlap_start)
        })
        .sum()
}
