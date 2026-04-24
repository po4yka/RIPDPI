//! Unified protocol classification for payload buffers.
//!
//! Wraps existing stateless `is_*()` / `parse_*()` detection functions behind
//! a common [`ProtocolClassifier`] trait with a registry for ordered lookup.

use ripdpi_collections::enum_map::{EnumKey, EnumMap};

use crate::{is_http, is_tls_client_hello, parse_http, parse_tls};

/// Protocol identifier for dispatch.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum ProtocolId {
    Http = 0,
    Tls = 1,
    Quic = 2,
    Ssh = 3,
}

impl EnumKey for ProtocolId {
    const COUNT: usize = 4;
    fn index(self) -> usize {
        self as usize
    }
}

impl ProtocolId {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Http => "http",
            Self::Tls => "tls",
            Self::Quic => "quic",
            Self::Ssh => "ssh",
        }
    }
}

/// Result of protocol classification on a payload buffer.
#[derive(Debug, Clone)]
pub struct ClassifyResult {
    pub protocol: ProtocolId,
    pub host: Option<String>,
}

/// Trait for single-buffer protocol detection.
///
/// Implementations wrap existing `is_*()` / `parse_*()` functions.
/// Must be stateless and allocation-free on the negative path.
pub trait ProtocolClassifier: Send + Sync {
    /// The protocol this classifier detects.
    fn id(&self) -> ProtocolId;

    /// Returns true if the payload matches this protocol.
    fn matches(&self, payload: &[u8]) -> bool;

    /// Extract host/SNI from a matching payload.
    fn extract_host(&self, payload: &[u8]) -> Option<String>;
}

/// Ordered registry of protocol classifiers.
///
/// Tries classifiers in priority order (TLS before HTTP to avoid false
/// positives on TLS record bytes) for `classify()`, and provides O(1)
/// lookup by [`ProtocolId`] via an [`EnumMap`] for `get()`.
pub struct ClassifierRegistry {
    /// Ordered list for sequential classify() (priority-based).
    ordered: Vec<Box<dyn ProtocolClassifier>>,
    /// O(1) lookup by ProtocolId (indexes into `ordered`).
    by_id: EnumMap<ProtocolId, usize>,
}

impl ClassifierRegistry {
    pub fn new(classifiers: Vec<Box<dyn ProtocolClassifier>>) -> Self {
        let mut by_id = EnumMap::new();
        for (i, c) in classifiers.iter().enumerate() {
            by_id.insert(c.id(), i);
        }
        Self { ordered: classifiers, by_id }
    }

    /// Classify a payload by trying each classifier in order.
    pub fn classify(&self, payload: &[u8]) -> Option<ClassifyResult> {
        for classifier in &self.ordered {
            if classifier.matches(payload) {
                return Some(ClassifyResult { protocol: classifier.id(), host: classifier.extract_host(payload) });
            }
        }
        None
    }

    /// Classify without extracting the host (faster, no allocations).
    pub fn classify_id(&self, payload: &[u8]) -> Option<ProtocolId> {
        self.ordered.iter().find(|c| c.matches(payload)).map(|c| c.id())
    }

    /// Get a specific classifier by protocol ID. O(1) via EnumMap.
    pub fn get(&self, id: ProtocolId) -> Option<&dyn ProtocolClassifier> {
        self.by_id.get(id).and_then(|&idx| self.ordered.get(idx)).map(AsRef::as_ref)
    }
}

// ---------------------------------------------------------------------------
// Built-in classifiers
// ---------------------------------------------------------------------------

struct TlsClassifier;

impl ProtocolClassifier for TlsClassifier {
    fn id(&self) -> ProtocolId {
        ProtocolId::Tls
    }

    fn matches(&self, payload: &[u8]) -> bool {
        is_tls_client_hello(payload)
    }

    fn extract_host(&self, payload: &[u8]) -> Option<String> {
        parse_tls(payload).map(|h| String::from_utf8_lossy(h).into_owned())
    }
}

struct HttpClassifier;

impl ProtocolClassifier for HttpClassifier {
    fn id(&self) -> ProtocolId {
        ProtocolId::Http
    }

    fn matches(&self, payload: &[u8]) -> bool {
        is_http(payload)
    }

    fn extract_host(&self, payload: &[u8]) -> Option<String> {
        parse_http(payload).map(|h| String::from_utf8_lossy(h.host).into_owned())
    }
}

struct SshClassifier;

impl ProtocolClassifier for SshClassifier {
    fn id(&self) -> ProtocolId {
        ProtocolId::Ssh
    }

    fn matches(&self, payload: &[u8]) -> bool {
        payload.starts_with(b"SSH-")
    }

    fn extract_host(&self, _payload: &[u8]) -> Option<String> {
        None // SSH doesn't carry a hostname in its banner
    }
}

/// Build the default classifier registry.
///
/// Priority order: TLS → HTTP. TLS is checked first because a TLS
/// ClientHello must not be misidentified as HTTP.
///
/// QUIC is intentionally excluded: it requires runtime config context
/// (version toggles, disabled mode) that the stateless classifier cannot
/// provide. QUIC detection remains special-cased at call sites.
pub fn default_registry() -> ClassifierRegistry {
    ClassifierRegistry::new(vec![Box::new(TlsClassifier), Box::new(HttpClassifier), Box::new(SshClassifier)])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tls_client_hello() -> Vec<u8> {
        // Minimal TLS 1.2 ClientHello with SNI "example.com"
        let sni = b"example.com";
        let sni_len = sni.len();

        // SNI extension: type(2) + ext_len(2) + list_len(2) + host_type(1) + name_len(2) + name
        let sni_ext_len = 2 + 1 + 2 + sni_len;
        let ext_total_len = 2 + 2 + sni_ext_len; // type + len + data

        // Handshake: type(1) + len(3) + version(2) + random(32) + session_id_len(1) +
        //            cipher_suites_len(2) + cipher(2) + comp_len(1) + comp(1) + ext_len(2) + ext
        let handshake_len = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + ext_total_len;

        let mut pkt = Vec::new();
        // TLS record header
        pkt.push(0x16); // ContentType: Handshake
        pkt.extend([0x03, 0x01]); // Version: TLS 1.0
        pkt.extend(((handshake_len + 4) as u16).to_be_bytes()); // Record length

        // Handshake header
        pkt.push(0x01); // HandshakeType: ClientHello
        pkt.push(0x00);
        pkt.extend((handshake_len as u16).to_be_bytes());

        // ClientHello body
        pkt.extend([0x03, 0x03]); // Version: TLS 1.2
        pkt.extend([0u8; 32]); // Random
        pkt.push(0x00); // Session ID length: 0
        pkt.extend(2u16.to_be_bytes()); // Cipher suites length
        pkt.extend([0x00, 0x2F]); // TLS_RSA_WITH_AES_128_CBC_SHA
        pkt.push(0x01); // Compression methods length
        pkt.push(0x00); // No compression

        // Extensions length
        pkt.extend((ext_total_len as u16).to_be_bytes());

        // SNI extension (type 0x0000)
        pkt.extend(0u16.to_be_bytes()); // Extension type: server_name
        pkt.extend((sni_ext_len as u16).to_be_bytes()); // Extension data length
        pkt.extend(((sni_ext_len - 2) as u16).to_be_bytes()); // Server name list length
        pkt.push(0x00); // Host name type
        pkt.extend((sni_len as u16).to_be_bytes()); // Host name length
        pkt.extend(sni);

        pkt
    }

    fn http_get_request() -> Vec<u8> {
        b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".to_vec()
    }

    #[test]
    fn classify_tls_client_hello() {
        let registry = default_registry();
        let payload = tls_client_hello();
        let result = registry.classify(&payload).expect("should classify as TLS");
        assert_eq!(result.protocol, ProtocolId::Tls);
        assert_eq!(result.host.as_deref(), Some("example.com"));
    }

    #[test]
    fn classify_http_get() {
        let registry = default_registry();
        let payload = http_get_request();
        let result = registry.classify(&payload).expect("should classify as HTTP");
        assert_eq!(result.protocol, ProtocolId::Http);
        assert_eq!(result.host.as_deref(), Some("example.com"));
    }

    #[test]
    fn classify_unknown_returns_none() {
        let registry = default_registry();
        let result = registry.classify(b"random garbage data here");
        assert!(result.is_none());
    }

    #[test]
    fn classify_empty_returns_none() {
        let registry = default_registry();
        assert!(registry.classify(b"").is_none());
    }

    #[test]
    fn classify_id_avoids_host_extraction() {
        let registry = default_registry();
        let payload = http_get_request();
        let id = registry.classify_id(&payload);
        assert_eq!(id, Some(ProtocolId::Http));
    }

    #[test]
    fn tls_has_priority_over_http() {
        // A TLS ClientHello should never be misidentified as HTTP
        let registry = default_registry();
        let payload = tls_client_hello();
        assert!(!is_http(&payload), "TLS payload must not match HTTP");
        let result = registry.classify(&payload).unwrap();
        assert_eq!(result.protocol, ProtocolId::Tls);
    }

    #[test]
    fn get_classifier_by_id() {
        let registry = default_registry();
        let tls = registry.get(ProtocolId::Tls);
        assert!(tls.is_some());
        assert_eq!(tls.unwrap().id(), ProtocolId::Tls);

        let http = registry.get(ProtocolId::Http);
        assert!(http.is_some());
        assert_eq!(http.unwrap().id(), ProtocolId::Http);
    }

    #[test]
    fn classify_ssh_banner() {
        let registry = default_registry();
        let payload = b"SSH-2.0-OpenSSH_9.6\r\n";
        let result = registry.classify(payload).expect("should classify as SSH");
        assert_eq!(result.protocol, ProtocolId::Ssh);
        assert_eq!(result.host, None); // SSH has no host field
    }

    #[test]
    fn protocol_id_as_str() {
        assert_eq!(ProtocolId::Http.as_str(), "http");
        assert_eq!(ProtocolId::Tls.as_str(), "tls");
        assert_eq!(ProtocolId::Quic.as_str(), "quic");
        assert_eq!(ProtocolId::Ssh.as_str(), "ssh");
    }
}
