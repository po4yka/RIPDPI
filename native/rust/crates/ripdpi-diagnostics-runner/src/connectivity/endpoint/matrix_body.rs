//! Bounded HTTP/1.1 framing. No response payload is retained in a report.
use std::io::Read;

pub(super) struct BodyResult {
    pub status: u16,
    pub headers_complete: bool,
    pub bytes: usize,
    pub complete: bool,
    pub reason: &'static str,
}

struct Reader<'a, R> {
    stream: &'a mut R,
    abort: &'a dyn Fn() -> bool,
    wire_bytes: usize,
    wire_limit: usize,
}
impl<R: Read> Reader<'_, R> {
    fn byte(&mut self) -> Result<Option<u8>, &'static str> {
        if (self.abort)() {
            return Err("cancelled");
        }
        if self.wire_bytes >= self.wire_limit {
            return Err("response_limit");
        }
        let mut byte = [0];
        match self.stream.read(&mut byte) {
            Ok(0) => Ok(None),
            Ok(_) => {
                self.wire_bytes += 1;
                Ok(Some(byte[0]))
            }
            Err(_) => Err("read_error"),
        }
    }
    fn line(&mut self, limit: usize) -> Result<Vec<u8>, &'static str> {
        let mut bytes = Vec::new();
        loop {
            bytes.push(self.byte()?.ok_or("early_eof")?);
            if bytes.ends_with(b"\r\n") {
                bytes.truncate(bytes.len() - 2);
                return Ok(bytes);
            }
            if bytes.len() >= limit {
                return Err("header_limit");
            }
        }
    }
    fn exact(&mut self, size: usize, bytes: &mut usize) -> Result<(), &'static str> {
        for _ in 0..size {
            self.byte()?.ok_or("early_eof")?;
            *bytes += 1;
        }
        Ok(())
    }
}

pub(super) fn read_response<R: Read>(stream: &mut R, max_body: usize, abort: &dyn Fn() -> bool) -> BodyResult {
    let mut reader = Reader { stream, abort, wire_bytes: 0, wire_limit: max_body + 16384 };
    let mut result =
        BodyResult { headers_complete: false, status: 0, bytes: 0, complete: false, reason: "malformed_http" };
    match parse(&mut reader, max_body, &mut result) {
        Ok(()) => {
            result.complete = true;
            result.reason = "complete";
        }
        Err(reason) => result.reason = reason,
    }
    result
}

fn parse<R: Read>(reader: &mut Reader<'_, R>, max: usize, result: &mut BodyResult) -> Result<(), &'static str> {
    let status = reader.line(16384)?;
    let status = std::str::from_utf8(&status).map_err(|_| "malformed_http")?;
    let mut parts = status.split_whitespace();
    if !matches!(parts.next(), Some("HTTP/1.1" | "HTTP/1.0")) {
        return Err("malformed_http");
    }
    result.status = parts.next().ok_or("malformed_http")?.parse().map_err(|_| "malformed_http")?;
    if !(200..=599).contains(&result.status) {
        return Err("unsupported_status");
    }
    let mut length = None;
    let mut chunked = false;
    loop {
        let line = reader.line(16384usize.saturating_sub(reader.wire_bytes).max(1))?;
        if line.is_empty() {
            break;
        }
        let line = std::str::from_utf8(&line).map_err(|_| "malformed_http")?;
        let (name, value) = line.split_once(':').ok_or("malformed_http")?;
        if name.eq_ignore_ascii_case("content-length") {
            if length.is_some() {
                return Err("ambiguous_framing");
            }
            length = Some(value.trim().parse::<usize>().map_err(|_| "malformed_http")?);
        }
        if name.eq_ignore_ascii_case("transfer-encoding") {
            if chunked || !value.trim().eq_ignore_ascii_case("chunked") {
                return Err("ambiguous_framing");
            }
            chunked = true;
        }
        if reader.wire_bytes > 16384 {
            return Err("header_limit");
        }
    }
    result.headers_complete = true;
    if chunked && length.is_some() {
        return Err("ambiguous_framing");
    }
    if !(200..300).contains(&result.status) {
        return Err("http_status");
    }
    if result.status == 206 {
        return Err("partial_response");
    }
    if matches!(result.status, 204 | 205) {
        return Err("empty_body");
    }
    if chunked {
        loop {
            let line = reader.line(1024)?;
            let line = std::str::from_utf8(&line).map_err(|_| "malformed_chunk")?;
            let size = usize::from_str_radix(line.split(';').next().ok_or("malformed_chunk")?.trim(), 16)
                .map_err(|_| "malformed_chunk")?;
            if size == 0 {
                // Trailers are bounded by the total wire budget and are discarded.
                loop {
                    if reader.line(1024)?.is_empty() {
                        break;
                    }
                }
                break;
            }
            if size > max.saturating_sub(result.bytes) {
                return Err("response_limit");
            }
            reader.exact(size, &mut result.bytes)?;
            if !reader.line(2)?.is_empty() {
                return Err("malformed_chunk");
            }
        }
    } else if let Some(size) = length {
        if size > max {
            return Err("response_limit");
        }
        reader.exact(size, &mut result.bytes)?;
    } else {
        while reader.byte()?.is_some() {
            result.bytes += 1;
            if result.bytes > max {
                return Err("response_limit");
            }
        }
    }
    if result.bytes == 0 {
        return Err("empty_body");
    }
    Ok(())
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
