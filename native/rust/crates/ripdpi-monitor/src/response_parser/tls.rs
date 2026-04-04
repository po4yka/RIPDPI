//! TLS response parser for diagnostics probes.

use ripdpi_packets::classify::ProtocolId;

use super::{ParsedResponse, ResponseParser, ResponseParserFactory};

pub(crate) struct TlsResponseParserFactory;

impl ResponseParserFactory for TlsResponseParserFactory {
    fn protocol_id(&self) -> ProtocolId {
        ProtocolId::Tls
    }

    fn create(&self) -> Box<dyn ResponseParser> {
        Box::new(TlsResponseParser { buffer: Vec::new() })
    }
}

struct TlsResponseParser {
    buffer: Vec<u8>,
}

impl ResponseParser for TlsResponseParser {
    fn feed(&mut self, data: &[u8]) {
        self.buffer.extend_from_slice(data);
    }

    fn finish(self: Box<Self>) -> ParsedResponse {
        let mut response = ParsedResponse { protocol: Some(ProtocolId::Tls), ..Default::default() };

        let buf = &self.buffer;

        // Detect TLS Alert record (content type 0x15).
        if buf.len() >= 7 && buf[0] == 0x15 && buf[1] == 0x03 {
            let alert_level = buf.get(5).copied().unwrap_or(0);
            let alert_code = buf.get(6).copied().unwrap_or(0);
            response.tls_alert_code = Some(alert_code);
            response.extra.push(("tlsAlertLevel".into(), alert_level.to_string()));
            return response;
        }

        // Detect TLS ServerHello (content type 0x16, handshake type 0x02).
        if buf.len() >= 11 && buf[0] == 0x16 && buf[1] == 0x03 {
            // Record header: type(1) + version(2) + length(2) = 5 bytes
            // Handshake header: type(1) + length(3) = 4 bytes
            // ServerHello body: version(2)
            if buf[5] == 0x02 {
                let major = buf[9];
                let minor = buf[10];
                let version = match (major, minor) {
                    (0x03, 0x03) => "TLS 1.2",
                    (0x03, 0x04) => "TLS 1.3",
                    (0x03, 0x02) => "TLS 1.1",
                    (0x03, 0x01) => "TLS 1.0",
                    (0x03, 0x00) => "SSL 3.0",
                    _ => "unknown",
                };
                response.tls_version = Some(version.to_string());

                // TLS 1.3 uses 0x0303 in the record and signals via
                // supported_versions extension. We report the record-level
                // version here; full detection requires extension parsing.
            }
        }

        response
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_tls_alert() {
        let factory = TlsResponseParserFactory;
        let mut parser = factory.create();
        // TLS Alert: content_type=0x15, version=0x0303, length=2, level=2(fatal), code=40(handshake_failure)
        parser.feed(&[0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28]);
        let result = parser.finish();
        assert_eq!(result.protocol, Some(ProtocolId::Tls));
        assert_eq!(result.tls_alert_code, Some(0x28));
    }

    #[test]
    fn parse_tls12_server_hello() {
        let factory = TlsResponseParserFactory;
        let mut parser = factory.create();
        // Minimal TLS 1.2 ServerHello
        let mut pkt = vec![
            0x16, 0x03, 0x03, 0x00, 0x30, // Record header
            0x02, 0x00, 0x00, 0x2C, // Handshake header (ServerHello)
            0x03, 0x03, // Version: TLS 1.2
        ];
        pkt.extend([0u8; 32]); // Random
        pkt.push(0x00); // Session ID length
        pkt.extend([0x00, 0x2F]); // Cipher suite
        pkt.push(0x00); // Compression method
        parser.feed(&pkt);
        let result = parser.finish();
        assert_eq!(result.tls_version.as_deref(), Some("TLS 1.2"));
    }

    #[test]
    fn parse_empty_buffer() {
        let factory = TlsResponseParserFactory;
        let parser = factory.create();
        let result = parser.finish();
        assert_eq!(result.tls_version, None);
        assert_eq!(result.tls_alert_code, None);
    }

    #[test]
    fn parse_ssl30() {
        let factory = TlsResponseParserFactory;
        let mut parser = factory.create();
        let pkt = vec![
            0x16, 0x03, 0x00, 0x00, 0x30, // Record: SSL 3.0
            0x02, 0x00, 0x00, 0x2C, // ServerHello
            0x03, 0x00, // Version: SSL 3.0
        ];
        parser.feed(&pkt);
        let result = parser.finish();
        assert_eq!(result.tls_version.as_deref(), Some("SSL 3.0"));
    }
}
