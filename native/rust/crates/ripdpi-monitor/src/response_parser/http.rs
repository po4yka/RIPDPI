//! HTTP response parser for diagnostics probes.

use ripdpi_packets::classify::ProtocolId;

use super::{ParsedResponse, ResponseParser, ResponseParserFactory};

pub(crate) struct HttpResponseParserFactory;

impl ResponseParserFactory for HttpResponseParserFactory {
    fn protocol_id(&self) -> ProtocolId {
        ProtocolId::Http
    }

    fn create(&self) -> Box<dyn ResponseParser> {
        Box::new(HttpResponseParser { buffer: Vec::new() })
    }
}

struct HttpResponseParser {
    buffer: Vec<u8>,
}

impl ResponseParser for HttpResponseParser {
    fn feed(&mut self, data: &[u8]) {
        self.buffer.extend_from_slice(data);
    }

    fn finish(self: Box<Self>) -> ParsedResponse {
        let mut response = ParsedResponse { protocol: Some(ProtocolId::Http), ..Default::default() };

        let buf = &self.buffer;
        if buf.len() < 12 {
            return response;
        }

        // Parse status line: "HTTP/1.x NNN reason\r\n"
        if let Some(end) = buf.windows(2).position(|w| w == b"\r\n") {
            let status_line = &buf[..end];
            if let Some(code_start) = status_line.iter().position(|&b| b == b' ') {
                let code_slice = &status_line[code_start + 1..];
                if code_slice.len() >= 3 {
                    if let Ok(code) = std::str::from_utf8(&code_slice[..3]).unwrap_or("0").parse::<u16>() {
                        response.status_code = Some(code);
                        response.is_redirect = (300..=399).contains(&code);
                    }
                }
            }
        }

        // Scan headers for Location (redirect target) and blockpage indicators.
        if let Some(header_end) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            let headers_raw = &buf[..header_end];
            for line in headers_raw.split(|&b| b == b'\n') {
                let line = line.strip_suffix(b"\r").unwrap_or(line);
                if let Some(value) = strip_header_prefix(line, b"location:") {
                    response.redirect_location = Some(String::from_utf8_lossy(value.trim_ascii()).into_owned());
                }
            }

            // Simple blockpage heuristic: very short body with a redirect or
            // specific status codes often used by censorship equipment.
            let body = &buf[header_end + 4..];
            if matches!(response.status_code, Some(403) | Some(451)) {
                response.is_blockpage = true;
            } else if response.is_redirect && body.is_empty() {
                // 3xx with empty body is suspicious but not definitive.
                response.extra.push(("emptyRedirectBody".into(), "true".into()));
            }
        }

        response
    }
}

fn strip_header_prefix<'a>(line: &'a [u8], prefix: &[u8]) -> Option<&'a [u8]> {
    if line.len() > prefix.len() && line[..prefix.len()].eq_ignore_ascii_case(prefix) {
        Some(&line[prefix.len()..])
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_200_ok() {
        let factory = HttpResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html></html>");
        let result = parser.finish();
        assert_eq!(result.protocol, Some(ProtocolId::Http));
        assert_eq!(result.status_code, Some(200));
        assert!(!result.is_redirect);
        assert!(!result.is_blockpage);
    }

    #[test]
    fn parse_302_redirect() {
        let factory = HttpResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"HTTP/1.1 302 Found\r\nLocation: http://blocked.isp.example/\r\n\r\n");
        let result = parser.finish();
        assert_eq!(result.status_code, Some(302));
        assert!(result.is_redirect);
        assert_eq!(result.redirect_location.as_deref(), Some("http://blocked.isp.example/"));
    }

    #[test]
    fn parse_403_blockpage() {
        let factory = HttpResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"HTTP/1.1 403 Forbidden\r\n\r\nAccess denied");
        let result = parser.finish();
        assert_eq!(result.status_code, Some(403));
        assert!(result.is_blockpage);
    }

    #[test]
    fn parse_empty_buffer() {
        let factory = HttpResponseParserFactory;
        let parser = factory.create();
        let result = parser.finish();
        assert_eq!(result.status_code, None);
    }

    #[test]
    fn incremental_feed() {
        let factory = HttpResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"HTTP/1.1 ");
        parser.feed(b"200 OK\r\n\r\n");
        let result = parser.finish();
        assert_eq!(result.status_code, Some(200));
    }
}
