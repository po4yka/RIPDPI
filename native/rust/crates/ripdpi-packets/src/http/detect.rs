use super::parse::{http_method_start, parse_http_parts};
use crate::types::{HttpHost, HttpMarkerInfo};

pub fn is_http(buffer: &[u8]) -> bool {
    http_method_start(buffer).is_some()
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
