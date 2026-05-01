use std::io::Write;

use crate::http::read_http_headers;
use crate::transport::ConnectionStream;
use crate::util::MAX_HTTP_BYTES;

pub(super) fn write_padded_head_request(
    stream: &mut ConnectionStream,
    index: usize,
    host_header: &str,
) -> std::io::Result<()> {
    let payload = padded_head_request(index, host_header);
    stream.write_all(payload.as_bytes()).and_then(|_| stream.flush())
}

pub(super) fn read_response_headers(stream: &mut ConnectionStream) -> Result<(), String> {
    read_http_headers(stream, MAX_HTTP_BYTES).map(|_| ())
}

pub(super) fn normalized_host_header(host_header: &str) -> &str {
    if host_header.is_empty() {
        "localhost"
    } else {
        host_header
    }
}

pub(super) fn padded_head_request_len(index: usize, host_header: &str) -> usize {
    padded_head_request(index, host_header).len()
}

fn padded_head_request(index: usize, host_header: &str) -> String {
    let pad = "A".repeat(8 * 1024 + (index * 128));
    format!("HEAD / HTTP/1.1\r\nHost: {host_header}\r\nConnection: keep-alive\r\nX-Pad: {pad}\r\n\r\n")
}
