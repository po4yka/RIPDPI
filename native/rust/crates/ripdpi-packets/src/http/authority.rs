use std::net::Ipv6Addr;

use super::detect::http_request_head_end;
use super::parse::parse_http_request_layout;
use crate::types::HttpHost;
use crate::util::parse_u16_ascii;

/// Extracts an unambiguous ASCII DNS/IP authority for policy decisions.
/// Raw Host mutation offsets remain available through `http_marker_info`.
pub fn http_request_authority(buffer: &[u8]) -> Option<HttpHost<'_>> {
    let head_end = http_request_head_end(buffer)?;
    let head = &buffer[..head_end];
    let layout = parse_http_request_layout(head)?;
    let line = &head[layout.method_start..layout.request_line_end];
    let mut fields = line.split(|byte| matches!(byte, b' ' | b'\t')).filter(|field| !field.is_empty());
    let method = fields.next()?;
    let target = fields.next()?;

    let mut host = None;
    for header in &layout.header_lines {
        let raw = &head[header.start..header.end];
        if matches!(raw.first(), Some(b' ' | b'\t')) {
            return None;
        }
        let colon = raw.iter().position(|byte| *byte == b':')?;
        let name = &raw[..colon];
        if name.trim_ascii().eq_ignore_ascii_case(b"host") {
            if !name.eq_ignore_ascii_case(b"host") || host.is_some() {
                return None;
            }
            let value = &raw[colon + 1..];
            if value.iter().any(|byte| byte.is_ascii_control() && *byte != b'\t') {
                return None;
            }
            host = Some(parse_authority(value.trim_ascii(), 80, false)?);
        }
    }

    if method == b"CONNECT" {
        return parse_authority(target, 80, true);
    }
    if target.starts_with(b"/") || target == b"*" {
        return host;
    }
    let (scheme_len, default_port) = if target.get(..7).is_some_and(|part| part.eq_ignore_ascii_case(b"http://")) {
        (7, 80)
    } else if target.get(..8).is_some_and(|part| part.eq_ignore_ascii_case(b"https://")) {
        (8, 443)
    } else {
        return None;
    };
    let uri = &target[scheme_len..];
    let authority_end = uri.iter().position(|byte| matches!(byte, b'/' | b'?' | b'#')).unwrap_or(uri.len());
    parse_authority(&uri[..authority_end], default_port, false)
}

fn parse_authority(value: &[u8], default_port: u16, require_port: bool) -> Option<HttpHost<'_>> {
    let (host, port) = if value.first() == Some(&b'[') {
        let close = value.iter().position(|byte| *byte == b']')?;
        let host = &value[1..close];
        std::str::from_utf8(host).ok()?.parse::<Ipv6Addr>().ok()?;
        (host, value.get(close + 1..)?)
    } else if let Some(colon) = value.iter().position(|byte| *byte == b':') {
        (&value[..colon], &value[colon..])
    } else {
        (value, &b""[..])
    };
    if host.is_empty()
        || (value.first() != Some(&b'[')
            && !host.iter().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_')))
    {
        return None;
    }
    let port = if port.is_empty() && !require_port {
        default_port
    } else {
        let digits = port.strip_prefix(b":")?;
        if digits.is_empty() || !digits.iter().all(u8::is_ascii_digit) {
            return None;
        }
        parse_u16_ascii(digits)?
    };
    Some(HttpHost { host, port })
}

#[cfg(test)]
mod tests;
