use std::ops::Range;

use crate::types::{ProtoInfo, TlsProtoInfo};
use ripdpi_config::OffsetProto;
use ripdpi_packets::{http_marker_info, tls_marker_info, IS_HTTP, IS_HTTPS};

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

pub(crate) fn init_proto_info(buffer: &[u8], info: &mut ProtoInfo) {
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
    if let Some(markers) = tls_marker_info(buffer) {
        return build_tls_proto_info(buffer, markers);
    }
    parse_multi_record_tls_proto_info(buffer)
}

fn build_tls_proto_info(buffer: &[u8], markers: ripdpi_packets::TlsMarkerInfo) -> Option<TlsProtoInfo> {
    let host = buffer.get(markers.host_start..markers.host_end)?.to_vec().into_boxed_slice();
    Some(TlsProtoInfo {
        markers,
        host_bytes: host,
        host_spans: vec![markers.host_start..markers.host_end].into_boxed_slice(),
    })
}

fn parse_multi_record_tls_proto_info(buffer: &[u8]) -> Option<TlsProtoInfo> {
    let (header, payload_spans) = collect_tls_record_payload_spans(buffer)?;
    let flattened_len = payload_spans.iter().map(|span| span.end.saturating_sub(span.start)).sum::<usize>();
    let flattened_len_u16 = u16::try_from(flattened_len).ok()?;
    let mut flattened = Vec::with_capacity(flattened_len);
    for span in &payload_spans {
        flattened.extend_from_slice(&buffer[span.start..span.end]);
    }

    let mut synthetic = Vec::with_capacity(flattened.len() + 5);
    synthetic.extend_from_slice(&header);
    synthetic.extend_from_slice(&flattened_len_u16.to_be_bytes());
    synthetic.extend_from_slice(&flattened);

    let synthetic_markers = tls_marker_info(&synthetic)?;
    let ext_len_start = map_flattened_start_offset(&payload_spans, synthetic_markers.ext_len_start.checked_sub(5)?)?;
    let ech_ext_start = synthetic_markers
        .ech_ext_start
        .and_then(|offset| map_flattened_start_offset(&payload_spans, offset.checked_sub(5)?));
    let sni_ext_start = map_flattened_start_offset(&payload_spans, synthetic_markers.sni_ext_start.checked_sub(5)?)?;
    let host_flat_start = synthetic_markers.host_start.checked_sub(5)?;
    let host_flat_end = synthetic_markers.host_end.checked_sub(5)?;
    let host_spans = extract_flattened_payload_spans(&payload_spans, host_flat_start, host_flat_end)?;
    let host_bytes = flattened.get(host_flat_start..host_flat_end)?.to_vec().into_boxed_slice();
    let host_start = host_spans.first()?.start;
    let host_end = host_spans.last()?.end;

    Some(TlsProtoInfo {
        markers: ripdpi_packets::TlsMarkerInfo { ext_len_start, ech_ext_start, sni_ext_start, host_start, host_end },
        host_bytes,
        host_spans: host_spans.into_boxed_slice(),
    })
}

fn collect_tls_record_payload_spans(buffer: &[u8]) -> Option<([u8; 3], Vec<Range<usize>>)> {
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

    (spans.len() >= 2 && cursor == buffer.len()).then_some((header, spans))
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
