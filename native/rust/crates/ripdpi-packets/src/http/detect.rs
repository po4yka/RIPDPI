use super::parse::{METHODS, http_method_start, parse_http_parts, parse_http_request_layout};
use crate::types::{HttpHost, HttpMarkerInfo};

/// Maximum head size accepted for HTTP request inspection.
pub const HTTP_REQUEST_HEAD_LIMIT: usize = 65_536;

pub fn is_http(buffer: &[u8]) -> bool {
    http_method_start(buffer).is_some()
}

/// True while bytes can be a supported HTTP request, including a split method.
pub fn is_http_request_prefix(buffer: &[u8]) -> bool {
    let mut buffer = buffer;
    for _ in 0..2 {
        if matches!(buffer.first(), Some(b'\r' | b'\n')) {
            buffer = &buffer[1..];
        }
    }
    METHODS.iter().any(|method| method.starts_with(buffer) || buffer.starts_with(method))
}

/// Returns the byte after a complete supported HTTP/1 request head within the inspection limit.
/// Does not require Host, and never includes body bytes in the result.
pub fn http_request_head_end(buffer: &[u8]) -> Option<usize> {
    let buffer = &buffer[..buffer.len().min(HTTP_REQUEST_HEAD_LIMIT)];
    let layout = parse_http_request_layout(buffer)?;
    let line = &buffer[layout.method_start..layout.request_line_end];
    if line.iter().any(|byte| byte.is_ascii_control() && *byte != b'\t') {
        return None;
    }
    let mut fields = line.split(|byte| matches!(byte, b' ' | b'\t')).filter(|field| !field.is_empty());
    fields.next()?;
    fields.next()?;
    let version = fields.next()?;
    (matches!(version, b"HTTP/1.0" | b"HTTP/1.1") && fields.next().is_none()).then_some(layout.body_start)
}

pub fn parse_http(buffer: &[u8]) -> Option<HttpHost<'_>> {
    let markers = http_marker_info(buffer)?;
    Some(HttpHost { host: &buffer[markers.host_start..markers.host_end], port: markers.port })
}

pub fn http_marker_info(buffer: &[u8]) -> Option<HttpMarkerInfo> {
    let parts = parse_http_parts(buffer)?;
    Some(HttpMarkerInfo {
        method_start: parts.method_start,
        host_start: parts.host_start,
        host_end: parts.host_end,
        port: parts.port,
    })
}

pub fn second_level_domain_span(host: &[u8]) -> Option<(usize, usize)> {
    if host.is_empty() {
        return None;
    }
    let mut end = host.len();
    for _ in 1..2 {
        while end > 0 && host[end - 1] != b'.' {
            end -= 1;
        }
        if end == 0 {
            return None;
        }
        end -= 1;
    }
    let mut start = end;
    while start > 0 && host[start - 1] != b'.' {
        start -= 1;
    }
    Some((start, end))
}
