//! Bounded HTTP/1.1 framing. No response payload is retained in a report.
use std::io::Read;

pub(super) struct BodyResult {
    pub status: u16,
    pub headers_complete: bool,
    pub bytes: usize,
    pub complete: bool,
    pub reason: &'static str,
}

pub(super) fn read_response<R: Read>(stream: &mut R, max_body: usize, abort: &dyn Fn() -> bool) -> BodyResult {
    let mut result =
        BodyResult { headers_complete: false, status: 0, bytes: 0, complete: false, reason: "malformed_http" };
    match parse(stream, max_body, abort, &mut result) {
        Ok(()) => {
            result.complete = true;
            result.reason = "complete";
        }
        Err(reason) => result.reason = reason,
    }
    result
}

fn parse<R: Read>(
    stream: &mut R,
    max: usize,
    abort: &dyn Fn() -> bool,
    result: &mut BodyResult,
) -> Result<(), &'static str> {
    use ripdpi_diagnostics_http::http::{HttpBodyFraming, HttpResponseDecoder, HttpResponseEvent};
    let mut decoder = HttpResponseDecoder::new("GET", 16384)?;
    let mut buffer = [0; 8192];
    let mut wire_remaining = max.saturating_add(16384);
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
                let count = stream.read(&mut buffer[..limit]).map_err(|_| "read_error")?;
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
            }
            HttpResponseEvent::Data(bytes) => {
                result.bytes = result.bytes.saturating_add(bytes.len());
                if result.bytes > max {
                    return Err("response_limit");
                }
            }
            HttpResponseEvent::Complete => return if result.bytes == 0 { Err("empty_body") } else { Ok(()) },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(mut response: &[u8], cap: usize) -> BodyResult {
        read_response(&mut response, cap, &|| false)
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
        assert_eq!(read_response(&mut b"".as_slice(), 20, &|| true).reason, "cancelled");
    }
    #[test]
    fn rejects_ambiguous_and_oversized_headers() {
        assert!(!run(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok", 20).complete);
        assert!(!run(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\nok", 20).complete);
        assert!(!run(&vec![b'a'; 17000], 20).complete);
    }
}
