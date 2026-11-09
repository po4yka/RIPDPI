use std::ops::Range;

use crate::types::{ProtoInfo, TlsProtoInfo};
use ripdpi_config::OffsetProto;
use ripdpi_packets::{IS_HTTP, IS_HTTPS, http_marker_info, parse_tls_client_hello_layout};

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
    let layout = parse_tls_client_hello_layout(buffer)?;
    let mut host_bytes = Vec::with_capacity(layout.markers.host_end - layout.markers.host_start);
    for span in &layout.host_spans {
        host_bytes.extend_from_slice(&buffer[span.clone()]);
    }
    Some(TlsProtoInfo {
        markers: layout.markers,
        host_bytes: host_bytes.into_boxed_slice(),
        host_spans: layout.host_spans.into_boxed_slice(),
    })
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
