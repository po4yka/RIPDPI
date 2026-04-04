//! SSH response parser for diagnostics probes.
//!
//! Extracts the SSH version banner (e.g., "SSH-2.0-OpenSSH_9.6") from the
//! server's initial response for protocol fingerprinting.

use ripdpi_packets::classify::ProtocolId;

use super::{ParsedResponse, ResponseParser, ResponseParserFactory};

pub(crate) struct SshResponseParserFactory;

impl ResponseParserFactory for SshResponseParserFactory {
    fn protocol_id(&self) -> ProtocolId {
        ProtocolId::Ssh
    }

    fn create(&self) -> Box<dyn ResponseParser> {
        Box::new(SshResponseParser { buffer: Vec::new() })
    }
}

struct SshResponseParser {
    buffer: Vec<u8>,
}

impl ResponseParser for SshResponseParser {
    fn feed(&mut self, data: &[u8]) {
        // Only accumulate up to the first line (banner ends at \r\n or \n).
        if self.buffer.windows(2).any(|w| w == b"\r\n") || self.buffer.contains(&b'\n') {
            return;
        }
        self.buffer.extend_from_slice(data);
    }

    fn finish(self: Box<Self>) -> ParsedResponse {
        let banner = if let Some(end) = self.buffer.iter().position(|&b| b == b'\n') {
            let line = &self.buffer[..end];
            let line = line.strip_suffix(b"\r").unwrap_or(line);
            String::from_utf8_lossy(line).into_owned()
        } else if !self.buffer.is_empty() {
            String::from_utf8_lossy(&self.buffer).into_owned()
        } else {
            return ParsedResponse { protocol: Some(ProtocolId::Ssh), ..Default::default() };
        };

        ParsedResponse {
            protocol: Some(ProtocolId::Ssh),
            extra: vec![("sshBanner".into(), banner)],
            ..Default::default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_openssh_banner() {
        let factory = SshResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"SSH-2.0-OpenSSH_9.6\r\n");
        let result = parser.finish();
        assert_eq!(result.protocol, Some(ProtocolId::Ssh));
        assert_eq!(result.extra.len(), 1);
        assert_eq!(result.extra[0].0, "sshBanner");
        assert_eq!(result.extra[0].1, "SSH-2.0-OpenSSH_9.6");
    }

    #[test]
    fn parse_dropbear_banner() {
        let factory = SshResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"SSH-2.0-dropbear_2024.86\n");
        let result = parser.finish();
        assert_eq!(result.extra[0].1, "SSH-2.0-dropbear_2024.86");
    }

    #[test]
    fn incremental_feed() {
        let factory = SshResponseParserFactory;
        let mut parser = factory.create();
        parser.feed(b"SSH-2.0-");
        parser.feed(b"OpenSSH_9.6\r\n");
        let result = parser.finish();
        assert_eq!(result.extra[0].1, "SSH-2.0-OpenSSH_9.6");
    }

    #[test]
    fn empty_buffer() {
        let factory = SshResponseParserFactory;
        let parser = factory.create();
        let result = parser.finish();
        assert_eq!(result.protocol, Some(ProtocolId::Ssh));
        assert!(result.extra.is_empty());
    }
}
