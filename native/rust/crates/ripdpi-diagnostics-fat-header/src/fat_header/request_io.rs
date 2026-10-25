use std::io::Write;
use std::net::IpAddr;

use crate::http::read_http_headers;
use crate::transport::ConnectionStream;
use crate::types::TcpTarget;
use crate::util::MAX_HTTP_BYTES;

pub(super) fn write_padded_head_request(
    stream: &mut ConnectionStream,
    index: usize,
    host_header: &str,
) -> std::io::Result<()> {
    let payload = padded_head_request(index, host_header);
    stream.write_all(payload.as_bytes()).and_then(|_| stream.flush())
}

/// Reads one response head and returns whether the server announced that it closes the connection.
pub(super) fn read_response_headers(stream: &mut ConnectionStream) -> Result<bool, String> {
    read_http_headers(stream, MAX_HTTP_BYTES).map(|headers| response_closes_connection(&headers))
}

/// `Connection: close`, or an HTTP/1.0 response without `Connection: keep-alive` (RFC 9112 section 9.3).
fn response_closes_connection(headers: &[u8]) -> bool {
    let text = String::from_utf8_lossy(headers);
    let mut lines = text.split("\r\n");
    let http_1_0 = lines.next().is_some_and(|status| status.starts_with("HTTP/1.0"));
    let mut close = false;
    let mut keep_alive = false;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else { continue };
        if name.trim().eq_ignore_ascii_case("connection") {
            for token in value.split(',').map(str::trim) {
                close |= token.eq_ignore_ascii_case("close");
                keep_alive |= token.eq_ignore_ascii_case("keep-alive");
            }
        }
    }
    close || (http_1_0 && !keep_alive)
}

/// The Host value to send: the caller's value when it is a plausible host, otherwise the target IP.
///
/// The runner falls back to the free-form provider label ("CDN77 #1 HTTP"), which is not a host;
/// strict servers answer it with `400` and close (RFC 9112 section 3.2).
pub(super) fn effective_host_header(target: &TcpTarget, host_header: &str) -> String {
    let is_label_fallback = host_header == target.provider
        && target.host_header.as_deref() != Some(host_header)
        && target.sni.as_deref() != Some(host_header);
    if !is_label_fallback && is_valid_host(host_header) {
        return host_header.to_string();
    }
    match target.ip.parse::<IpAddr>() {
        Ok(IpAddr::V6(ip)) => format!("[{ip}]"),
        Ok(IpAddr::V4(ip)) => ip.to_string(),
        Err(_) => "localhost".to_string(),
    }
}

/// A host name, IPv4 literal or bracketed IPv6 literal with an optional port (RFC 3986 section 3.2.2).
fn is_valid_host(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b':' | b'[' | b']'))
}

pub(super) fn padded_head_request_len(index: usize, host_header: &str) -> usize {
    padded_head_request(index, host_header).len()
}

fn padded_head_request(index: usize, host_header: &str) -> String {
    let pad = "A".repeat(8 * 1024 + (index * 128));
    format!("HEAD / HTTP/1.1\r\nHost: {host_header}\r\nConnection: keep-alive\r\nX-Pad: {pad}\r\n\r\n")
}
