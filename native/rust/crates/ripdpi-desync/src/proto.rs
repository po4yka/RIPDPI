use std::ops::Range;

use crate::first_flight_ir::{normalize_tls_client_hello_handshake_bytes, TlsClientHelloIr};
use crate::normalize_tls_client_hello;
use crate::types::{ProtoInfo, TlsProtoInfo};
use ripdpi_config::OffsetProto;
use ripdpi_packets::{http_marker_info, IS_HTTP, IS_HTTPS};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum HostOffsetBias {
    Start,
    End,
}

pub(crate) struct ResolvedHostRange<'a> {
    pub start: usize,
    pub end: usize,
    pub host: &'a [u8],
    spans: Option<&'a [Range<usize>]>,
}

impl<'a> ResolvedHostRange<'a> {
    pub fn start_offset(&self, host_index: usize) -> Option<usize> {
        self.map_offset(host_index, HostOffsetBias::Start)
    }

    pub fn end_offset(&self, host_index: usize) -> Option<usize> {
        self.map_offset(host_index, HostOffsetBias::End)
    }

    fn map_offset(&self, host_index: usize, bias: HostOffsetBias) -> Option<usize> {
        if let Some(spans) = self.spans {
            return map_fragmented_host_offset(spans, host_index, bias);
        }
        (host_index <= self.host.len()).then_some(self.start + host_index)
    }
}

pub fn init_proto_info(buffer: &[u8], info: &mut ProtoInfo) {
    if info.http.is_some() || info.tls.is_some() {
        return;
    }
    if let Some(tls) = parse_tls_proto_info(buffer) {
        info.kind = IS_HTTPS;
        info.tls = Some(tls);
    } else if let Some(http) = http_marker_info(buffer) {
        if info.kind == 0 {
            info.kind = IS_HTTP;
        }
        info.http = Some(http);
    }
}

pub(crate) fn resolve_host_range<'a>(
    buffer: &'a [u8],
    info: &'a mut ProtoInfo,
    proto: OffsetProto,
) -> Option<ResolvedHostRange<'a>> {
    init_proto_info(buffer, info);
    match proto {
        OffsetProto::TlsOnly => {
            let tls = info.tls.as_ref()?;
            Some(ResolvedHostRange {
                start: tls.host_start(),
                end: tls.host_end(),
                host: tls.host_bytes.as_ref(),
                spans: Some(tls.host_spans.as_ref()),
            })
        }
        OffsetProto::Any => {
            if let Some(tls) = info.tls.as_ref() {
                Some(ResolvedHostRange {
                    start: tls.host_start(),
                    end: tls.host_end(),
                    host: tls.host_bytes.as_ref(),
                    spans: Some(tls.host_spans.as_ref()),
                })
            } else {
                let http = info.http?;
                Some(ResolvedHostRange {
                    start: http.host_start,
                    end: http.host_end,
                    host: &buffer[http.host_start..http.host_end],
                    spans: None,
                })
            }
        }
    }
}

fn parse_tls_proto_info(buffer: &[u8]) -> Option<TlsProtoInfo> {
    if let Some(ir) = normalize_tls_client_hello(buffer) {
        return build_tls_proto_info_from_ir(buffer, &ir);
    }
    parse_multi_record_tls_proto_info(buffer)
}

fn build_tls_proto_info_from_ir(buffer: &[u8], ir: &TlsClientHelloIr) -> Option<TlsProtoInfo> {
    let sni_ext_start = ir
        .extensions
        .iter()
        .find(|extension| extension.ext_type == 0x0000)
        .map(|extension| extension.type_range.start + 4)?;
    let ech_ext_start =
        ir.extensions.iter().find(|extension| extension.ext_type == 0xfe0d).map(|extension| extension.type_range.start);
    let host = buffer.get(ir.authority_span.clone())?.to_vec().into_boxed_slice();
    #[allow(clippy::single_range_in_vec_init)]
    let host_spans = Box::new([ir.authority_span.clone()]);
    Some(TlsProtoInfo {
        markers: ripdpi_packets::TlsMarkerInfo {
            ext_len_start: ir.extensions_len_offset,
            ech_ext_start,
            sni_ext_start,
            host_start: ir.authority_span.start,
            host_end: ir.authority_span.end,
        },
        host_bytes: host,
        host_spans,
    })
}

fn parse_multi_record_tls_proto_info(buffer: &[u8]) -> Option<TlsProtoInfo> {
    let payload_spans = collect_tls_record_payload_spans(buffer)?;
    let flattened_len = payload_spans.iter().map(|span| span.end.saturating_sub(span.start)).sum::<usize>();
    let mut flattened = Vec::with_capacity(flattened_len);
    for span in &payload_spans {
        flattened.extend_from_slice(&buffer[span.start..span.end]);
    }

    let ir = normalize_tls_client_hello_handshake_bytes(&flattened)?;
    let ext_len_start = map_flattened_start_offset(&payload_spans, ir.extensions_len_offset)?;
    let ech_ext_start = ir
        .extensions
        .iter()
        .find(|extension| extension.ext_type == 0xfe0d)
        .and_then(|extension| map_flattened_start_offset(&payload_spans, extension.type_range.start));
    let sni_ext_start = ir
        .extensions
        .iter()
        .find(|extension| extension.ext_type == 0x0000)
        .and_then(|extension| map_flattened_start_offset(&payload_spans, extension.type_range.start + 4))?;
    let host_spans = extract_flattened_payload_spans(&payload_spans, ir.authority_span.start, ir.authority_span.end)?;
    let host_bytes = ir.authority.clone().into_boxed_slice();
    let host_start = host_spans.first()?.start;
    let host_end = host_spans.last()?.end;

    Some(TlsProtoInfo {
        markers: ripdpi_packets::TlsMarkerInfo { ext_len_start, ech_ext_start, sni_ext_start, host_start, host_end },
        host_bytes,
        host_spans: host_spans.into_boxed_slice(),
    })
}

fn collect_tls_record_payload_spans(buffer: &[u8]) -> Option<Vec<Range<usize>>> {
    if buffer.len() < 10 {
        return None;
    }
    let header = [*buffer.first()?, *buffer.get(1)?, *buffer.get(2)?];
    let mut spans = Vec::new();
    let mut cursor = 0usize;

    while cursor + 5 <= buffer.len() {
        if buffer.get(cursor..cursor + 3)? != header.as_slice() {
            return None;
        }
        let record_len = read_be_u16(buffer, cursor + 3)?;
        let payload_start = cursor + 5;
        let payload_end = payload_start.checked_add(record_len)?.min(buffer.len());
        spans.push(payload_start..payload_end);
        cursor = payload_end;
        if cursor == buffer.len() {
            break;
        }
    }

    (spans.len() >= 2 && cursor == buffer.len()).then_some(spans)
}

fn read_be_u16(buffer: &[u8], offset: usize) -> Option<usize> {
    let bytes = buffer.get(offset..offset + 2)?;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]) as usize)
}

fn map_flattened_start_offset(spans: &[Range<usize>], flat_offset: usize) -> Option<usize> {
    map_fragmented_host_offset(spans, flat_offset, HostOffsetBias::Start)
}

fn extract_flattened_payload_spans(
    spans: &[Range<usize>],
    host_flat_start: usize,
    host_flat_end: usize,
) -> Option<Vec<Range<usize>>> {
    if host_flat_start >= host_flat_end {
        return None;
    }
    let mut cursor = 0usize;
    let mut host_spans = Vec::new();
    let mut covered = 0usize;

    for span in spans {
        let span_len = span.end.saturating_sub(span.start);
        let flat_start = cursor;
        let flat_end = cursor + span_len;
        let overlap_start = host_flat_start.max(flat_start);
        let overlap_end = host_flat_end.min(flat_end);
        if overlap_start < overlap_end {
            let start = span.start + (overlap_start - flat_start);
            let end = span.start + (overlap_end - flat_start);
            host_spans.push(start..end);
            covered += overlap_end - overlap_start;
        }
        cursor = flat_end;
    }

    (covered == host_flat_end - host_flat_start).then_some(host_spans)
}

fn map_fragmented_host_offset(spans: &[Range<usize>], host_index: usize, bias: HostOffsetBias) -> Option<usize> {
    let mut cursor = 0usize;
    for (index, span) in spans.iter().enumerate() {
        let span_len = span.end.saturating_sub(span.start);
        if host_index < cursor + span_len {
            return Some(span.start + (host_index - cursor));
        }
        cursor += span_len;
        if host_index == cursor {
            return Some(match bias {
                HostOffsetBias::Start => spans.get(index + 1).map_or(span.end, |next| next.start),
                HostOffsetBias::End => span.end,
            });
        }
    }
    (host_index == cursor).then(|| spans.last().map(|span| span.end)).flatten()
}
