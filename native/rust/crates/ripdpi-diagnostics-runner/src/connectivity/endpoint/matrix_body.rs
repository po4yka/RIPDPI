//! Bounded HTTP/1.1 framing. No response payload is retained in a report.
use std::io::Read;

pub(super) struct BodyResult {
    pub status: u16,
    pub headers_complete: bool,
    pub bytes: usize,
    pub complete: bool,
    pub verified: bool,
    pub reason: &'static str,
}

pub(super) fn read_response<R: Read>(
    stream: &mut R,
    max_body: usize,
    abort: &dyn Fn() -> bool,
    verify_control_body: bool,
) -> BodyResult {
    let mut result = BodyResult {
        headers_complete: false,
        status: 0,
        bytes: 0,
        complete: false,
        verified: false,
        reason: "malformed_http",
    };
    match parse(stream, max_body, abort, verify_control_body, &mut result) {
        Ok(()) => {
            result.complete = true;
            result.reason = "complete";
        }
        Err(reason) => result.reason = reason,
    }
    result
}

fn read_failure(kind: std::io::ErrorKind) -> &'static str {
    use std::io::ErrorKind::{BrokenPipe, ConnectionAborted, ConnectionReset, Interrupted, TimedOut, WouldBlock};
    match kind {
        ConnectionReset | ConnectionAborted | BrokenPipe => "connection_reset",
        Interrupted | TimedOut | WouldBlock => "read_timeout",
        _ => "read_error",
    }
}

fn parse<R: Read>(
    stream: &mut R,
    max: usize,
    abort: &dyn Fn() -> bool,
    verify_control_body: bool,
    result: &mut BodyResult,
) -> Result<(), &'static str> {
    use ripdpi_diagnostics_http::http::{
        HttpBodyFraming, HttpResponseDecoder, HttpResponseEvent, classify_http_response_with_fingerprints,
    };
    let mut decoder = HttpResponseDecoder::new("GET", 16384)?;
    let mut buffer = [0; 8192];
    let mut wire_remaining = max.saturating_add(16384);
    let mut control_response = None;
    loop {
        if abort() {
            return Err("cancelled");
        }
        match decoder
            .next_event()
            .map_err(|reason| if reason == "response_truncated" { "early_eof" } else { reason })?
        {
            HttpResponseEvent::NeedInput => {
                let limit = decoder.input_capacity().min(wire_remaining);
                if limit == 0 {
                    return Err("response_limit");
                }
                let count = stream.read(&mut buffer[..limit]).map_err(|error| read_failure(error.kind()))?;
                wire_remaining -= count;
                if count == 0 {
                    decoder.finish_input();
                } else {
                    decoder.feed(&buffer[..count])?;
                }
            }
            HttpResponseEvent::Head(head, framing) => {
                result.status = head.status_code;
                result.headers_complete = true;
                if !(200..300).contains(&head.status_code) {
                    return Err("http_status");
                }
                if head.status_code == 206 {
                    return Err("partial_response");
                }
                if matches!(head.status_code, 204 | 205) {
                    return Err("empty_body");
                }
                if head.headers.get("content-length").is_some_and(|value| value.contains(',')) {
                    return Err("ambiguous_framing");
                }
                if matches!(framing, HttpBodyFraming::Length(length) if length > max as u64) {
                    return Err("response_limit");
                }
                if verify_control_body
                    && head.status_code == 200
                    && matches!(framing, HttpBodyFraming::Length(_) | HttpBodyFraming::Chunked)
                    && !head.headers.contains_key("content-range")
                    && head.headers.get("content-encoding").is_none_or(|coding| coding.eq_ignore_ascii_case("identity"))
                {
                    control_response = Some(head);
                }
            }
            HttpResponseEvent::Data(bytes) => {
                result.bytes = result.bytes.saturating_add(bytes.len());
                if result.bytes > max {
                    return Err("response_limit");
                }
                if let Some(response) = &mut control_response {
                    response.body.extend_from_slice(&bytes);
                }
            }
            HttpResponseEvent::Complete => {
                if result.bytes == 0 {
                    return Err("empty_body");
                }
                result.verified = control_response.is_some_and(|response| {
                    classify_http_response_with_fingerprints(
                        &response,
                        ripdpi_failure_classifier::bundled_blockpage_fingerprints(),
                    )
                    .0 == "http_ok"
                });
                return Ok(());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(mut response: &[u8], cap: usize) -> BodyResult {
        read_response(&mut response, cap, &|| false, false)
    }
    #[test]
    fn requires_complete_framing() {
        assert!(run(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", 20).complete);
        assert!(!run(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nok", 20).complete);
        assert!(run(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n", 20).complete);
        assert!(!run(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n", 20).complete);
    }
    #[test]
    fn does_not_confuse_server_errors_caps_or_empty_with_availability() {
        for response in [
            b"HTTP/1.1 503 Error\r\nContent-Length: 2\r\n\r\nok".as_slice(),
            b"HTTP/1.1 302 Found\r\nContent-Length: 2\r\n\r\nok",
            b"HTTP/1.1 204 Empty\r\n\r\n",
            b"HTTP/1.1 206 Partial Content\r\nContent-Length: 2\r\n\r\nok",
        ] {
            assert!(!run(response, 20).complete);
        }
        assert_eq!(run(b"HTTP/1.1 200 OK\r\nContent-Length: 200\r\n\r\n", 20).reason, "response_limit");
        assert_eq!(read_response(&mut b"".as_slice(), 20, &|| true, false).reason, "cancelled");
    }
    struct FailingReader(std::io::ErrorKind);
    impl Read for FailingReader {
        fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
            Err(self.0.into())
        }
    }
    #[test]
    fn separates_peer_reset_from_stalled_read() {
        let reason = |kind| read_response(&mut FailingReader(kind), 20, &|| false, false).reason;
        assert_eq!(reason(std::io::ErrorKind::ConnectionReset), "connection_reset");
        assert_eq!(reason(std::io::ErrorKind::ConnectionAborted), "connection_reset");
        assert_eq!(reason(std::io::ErrorKind::TimedOut), "read_timeout");
        assert_eq!(reason(std::io::ErrorKind::Interrupted), "read_timeout");
        assert_eq!(reason(std::io::ErrorKind::PermissionDenied), "read_error");
    }
    #[test]
    fn rejects_ambiguous_and_oversized_headers() {
        assert!(!run(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok", 20).complete);
        assert!(!run(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\nok", 20).complete);
        assert!(!run(&vec![b'a'; 17000], 20).complete);
    }
}
