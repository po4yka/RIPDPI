use std::io;
use std::time::{Duration, Instant};

use super::{
    FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT, FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT, OutboundTlsClientHelloAssembler,
    TlsRecordBoundaryTracker,
};

const HTTP_HEAD_LIMIT: usize = 65_536;

/// Assemble protocol headers before a hostname policy can release application bytes.
pub struct OutboundRequestAssembler {
    tls: OutboundTlsClientHelloAssembler,
    hostname_policy: bool,
    head: Vec<u8>,
    started_at: Option<Instant>,
}

impl OutboundRequestAssembler {
    pub fn new(hostname_policy: bool) -> Self {
        Self { tls: OutboundTlsClientHelloAssembler::new(), hostname_policy, head: Vec::new(), started_at: None }
    }

    pub fn push(&mut self, chunk: &[u8], now: Instant) -> io::Result<Option<Vec<u8>>> {
        if self.hostname_policy && self.tls.flush_on_timeout(now).is_some() {
            return Err(incomplete("incomplete TLS ClientHello"));
        }
        if self.hostname_policy
            && (!self.head.is_empty() || (self.started_at.is_none() && ripdpi_packets::is_http_request_prefix(chunk)))
        {
            let start = *self.started_at.get_or_insert(now);
            if now.saturating_duration_since(start) >= FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT {
                return Err(incomplete("incomplete HTTP request head"));
            }
            self.head.extend_from_slice(chunk);
            if !ripdpi_packets::is_http_request_prefix(&self.head) {
                return Ok(Some(std::mem::take(&mut self.head)));
            }
            let head_end = self
                .head
                .windows(4)
                .position(|b| b == b"\r\n\r\n")
                .map(|pos| pos + 4)
                .or_else(|| self.head.windows(2).position(|b| b == b"\n\n").map(|pos| pos + 2));
            if let Some(end) = head_end {
                if end > HTTP_HEAD_LIMIT {
                    return Err(incomplete("HTTP request head exceeds limit"));
                }
                return Ok(Some(std::mem::take(&mut self.head)));
            }
            if self.head.len() >= HTTP_HEAD_LIMIT {
                return Err(incomplete("HTTP request head exceeds limit"));
            }
            return Ok(None);
        }
        let tls_prefix = self.tls.is_buffering() || TlsRecordBoundaryTracker::looks_like_client_hello_prefix(chunk);
        let payload = self.tls.push(chunk, now);
        if self.hostname_policy
            && tls_prefix
            && payload.as_ref().is_some_and(|bytes| {
                bytes.len() >= FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT && ripdpi_packets::parse_tls(bytes).is_none()
            })
        {
            return Err(incomplete("first request exceeds inspection limit"));
        }
        Ok(payload)
    }

    pub fn timeout(&self, now: Instant) -> Option<Duration> {
        if !self.head.is_empty() {
            return self.started_at.map(|start| {
                FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT
                    .saturating_sub(now.saturating_duration_since(start))
                    .max(Duration::from_millis(1))
            });
        }
        self.tls.timeout(now)
    }

    pub fn flush_on_timeout(&mut self, now: Instant) -> io::Result<Option<Vec<u8>>> {
        if !self.head.is_empty() {
            if self
                .started_at
                .is_some_and(|start| now.saturating_duration_since(start) >= FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT)
            {
                return Err(incomplete("incomplete HTTP request head"));
            }
            return Ok(None);
        }
        let payload = self.tls.flush_on_timeout(now);
        if self.hostname_policy && payload.is_some() {
            return Err(incomplete("incomplete TLS ClientHello"));
        }
        Ok(payload)
    }

    pub fn finish(&mut self) -> io::Result<Option<Vec<u8>>> {
        if !self.head.is_empty() || (self.hostname_policy && self.tls.is_buffering()) {
            return Err(incomplete("incomplete first request"));
        }
        Ok(self.tls.finish())
    }

    pub fn is_buffering(&self) -> bool {
        !self.head.is_empty() || self.tls.is_buffering()
    }
}

fn incomplete(reason: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, reason)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn policy_assembly_preserves_http_body_and_binary_passthrough() {
        let now = Instant::now();
        let mut assembler = OutboundRequestAssembler::new(true);
        assert!(assembler.push(b"GE", now).unwrap().is_none());
        let rest = b"T / HTTP/1.1\r\nHost: example.test\r\n\r\nBODY";
        let mut expected = b"GE".to_vec();
        expected.extend(rest);
        assert_eq!(assembler.push(rest, now).unwrap(), Some(expected));
        assert_eq!(OutboundRequestAssembler::new(true).push(b"\0\xff", now).unwrap(), Some(vec![0, 255]));
        assert_eq!(OutboundRequestAssembler::new(false).push(b"G", now).unwrap(), Some(b"G".to_vec()));
        let mut binary = vec![0; FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT];
        binary[0] = 0x16;
        assert_eq!(OutboundRequestAssembler::new(true).push(&binary, now).unwrap(), Some(binary));
        let mut split_ping = OutboundRequestAssembler::new(true);
        assert!(split_ping.push(b"P", now).unwrap().is_none());
        assert_eq!(split_ping.push(b"ING", now).unwrap(), Some(b"PING".to_vec()));
        for plain in [&b"PING"[..], &b"EHLO host\r\n"[..]] {
            assert_eq!(OutboundRequestAssembler::new(true).push(plain, now).unwrap(), Some(plain.to_vec()));
        }
    }

    #[test]
    fn incomplete_policy_headers_fail_at_timeout_and_eof() {
        let now = Instant::now();
        for prefix in [&b"GET / HTTP/1.1\r\nHo"[..], &[0x16, 0x03, 0x03, 0, 100, 1][..]] {
            let mut assembler = OutboundRequestAssembler::new(true);
            assert!(assembler.push(prefix, now).unwrap().is_none());
            assert!(assembler.flush_on_timeout(now + Duration::from_secs(1)).is_err());
            let mut assembler = OutboundRequestAssembler::new(true);
            assert!(assembler.push(prefix, now).unwrap().is_none());
            assert!(assembler.finish().is_err());
            let mut assembler = OutboundRequestAssembler::new(true);
            assert!(assembler.push(prefix, now).unwrap().is_none());
            assert!(assembler.push(b"late", now + Duration::from_secs(1)).is_err());
        }
    }

    #[test]
    fn http_head_limit_excludes_body_and_rejects_a_complete_oversized_head() {
        let now = Instant::now();
        let mut oversized = b"GET / HTTP/1.1\r\nX: ".to_vec();
        oversized.extend(vec![b'a'; HTTP_HEAD_LIMIT]);
        oversized.extend(b"\r\n\r\n");
        assert!(OutboundRequestAssembler::new(true).push(&oversized, now).is_err());
        let mut body = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".to_vec();
        body.extend(vec![0; HTTP_HEAD_LIMIT]);
        assert_eq!(OutboundRequestAssembler::new(true).push(&body, now).unwrap(), Some(body));
    }
}
