//! Pluggable response parsing framework for diagnostics probes.
//!
//! Provides a [`ResponseParser`] trait for stateful response analysis and a
//! [`ResponseParserRegistry`] that maps [`ProtocolId`] to parser factories.

pub(crate) mod http;
pub(crate) mod ssh;
pub(crate) mod tls;

use ripdpi_packets::classify::ProtocolId;
use ripdpi_packets::fields::FieldObserver;

/// Parsed response fields relevant to diagnostics.
#[derive(Debug, Clone, Default)]
pub(crate) struct ParsedResponse {
    pub protocol: Option<ProtocolId>,
    pub status_code: Option<u16>,
    pub is_blockpage: bool,
    pub is_redirect: bool,
    pub redirect_location: Option<String>,
    pub tls_version: Option<String>,
    pub tls_alert_code: Option<u8>,
    pub cert_anomaly: bool,
    /// Protocol-specific key-value pairs for evidence reporting.
    pub extra: Vec<(String, String)>,
}

/// Trait for stateful response parsing in diagnostic probes.
///
/// Implementations accumulate bytes via [`feed`] and produce a
/// [`ParsedResponse`] when [`finish`] is called.
pub(crate) trait ResponseParser {
    /// Feed response bytes. May be called multiple times.
    fn feed(&mut self, data: &[u8]);

    /// Feed data and emit discovered fields to the observer.
    ///
    /// Default implementation delegates to [`feed`] with no field emission.
    /// Override to emit [`ProtocolField`](ripdpi_packets::fields::ProtocolField)
    /// variants as they are parsed.
    fn feed_observed(&mut self, data: &[u8], _observer: &mut dyn FieldObserver) {
        self.feed(data);
    }

    /// Finalize and return parsed fields.
    fn finish(self: Box<Self>) -> ParsedResponse;
}

/// Factory for creating per-probe parser instances.
pub(crate) trait ResponseParserFactory: Send + Sync {
    fn protocol_id(&self) -> ProtocolId;
    fn create(&self) -> Box<dyn ResponseParser>;
}

/// Registry mapping [`ProtocolId`] to [`ResponseParserFactory`].
pub(crate) struct ResponseParserRegistry {
    factories: Vec<Box<dyn ResponseParserFactory>>,
}

impl ResponseParserRegistry {
    pub fn new(factories: Vec<Box<dyn ResponseParserFactory>>) -> Self {
        Self { factories }
    }

    /// Create a parser for the given protocol, if a factory is registered.
    pub fn create(&self, id: ProtocolId) -> Option<Box<dyn ResponseParser>> {
        self.factories.iter().find(|f| f.protocol_id() == id).map(|f| f.create())
    }
}

/// Build the default response parser registry with HTTP and TLS parsers.
pub(crate) fn default_response_registry() -> ResponseParserRegistry {
    ResponseParserRegistry::new(vec![
        Box::new(http::HttpResponseParserFactory),
        Box::new(tls::TlsResponseParserFactory),
        Box::new(ssh::SshResponseParserFactory),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_creates_http_parser() {
        let registry = default_response_registry();
        let parser = registry.create(ProtocolId::Http);
        assert!(parser.is_some());
    }

    #[test]
    fn registry_creates_tls_parser() {
        let registry = default_response_registry();
        let parser = registry.create(ProtocolId::Tls);
        assert!(parser.is_some());
    }

    #[test]
    fn registry_returns_none_for_unknown() {
        let registry = default_response_registry();
        let parser = registry.create(ProtocolId::Quic);
        assert!(parser.is_none());
    }
}
