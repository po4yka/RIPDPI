//! Protocol field extraction with observer callbacks.
//!
//! Defines [`ProtocolField`] variants emitted during parsing and a
//! [`FieldObserver`] trait for receiving them. [`FieldCache`] collects
//! emitted fields for reuse by multiple downstream classifiers,
//! eliminating redundant re-parsing of the same response bytes.

/// Protocol field emitted during parsing.
///
/// Each variant carries the extracted value. Fields are emitted as they
/// are discovered -- consumers receive them via [`FieldObserver`] callbacks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolField {
    // -- HTTP --
    HttpStatusCode(u16),
    HttpHeader {
        name: String,
        value: String,
    },
    HttpBodyChunk(Vec<u8>),
    HttpRedirectLocation(String),

    // -- TLS --
    TlsAlertCode(u8),
    TlsAlertLevel(u8),
    TlsVersion(String),
    TlsServerHelloSeen,

    // -- Generic --
    HostName(String),
    /// Byte offset range of a named field in the original payload.
    FieldOffset {
        name: &'static str,
        start: usize,
        end: usize,
    },
}

/// Observer trait for receiving protocol fields during parsing.
pub trait FieldObserver {
    fn on_field(&mut self, field: &ProtocolField);
}

/// Collects all emitted fields for later query by downstream classifiers.
#[derive(Debug, Clone, Default)]
pub struct FieldCache {
    fields: Vec<ProtocolField>,
}

impl FieldCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn fields(&self) -> &[ProtocolField] {
        &self.fields
    }

    pub fn http_status_code(&self) -> Option<u16> {
        self.fields.iter().find_map(|f| match f {
            ProtocolField::HttpStatusCode(code) => Some(*code),
            _ => None,
        })
    }

    pub fn tls_alert_code(&self) -> Option<u8> {
        self.fields.iter().find_map(|f| match f {
            ProtocolField::TlsAlertCode(code) => Some(*code),
            _ => None,
        })
    }

    pub fn tls_version(&self) -> Option<&str> {
        self.fields.iter().find_map(|f| match f {
            ProtocolField::TlsVersion(v) => Some(v.as_str()),
            _ => None,
        })
    }

    pub fn http_header(&self, name: &str) -> Option<&str> {
        self.fields.iter().find_map(|f| match f {
            ProtocolField::HttpHeader { name: n, value } if n.eq_ignore_ascii_case(name) => Some(value.as_str()),
            _ => None,
        })
    }

    pub fn http_headers(&self, name: &str) -> Vec<&str> {
        self.fields
            .iter()
            .filter_map(|f| match f {
                ProtocolField::HttpHeader { name: n, value } if n.eq_ignore_ascii_case(name) => Some(value.as_str()),
                _ => None,
            })
            .collect()
    }

    pub fn redirect_location(&self) -> Option<&str> {
        self.fields.iter().find_map(|f| match f {
            ProtocolField::HttpRedirectLocation(loc) => Some(loc.as_str()),
            _ => None,
        })
    }

    pub fn has_tls_server_hello(&self) -> bool {
        self.fields.iter().any(|f| matches!(f, ProtocolField::TlsServerHelloSeen))
    }

    pub fn body_bytes(&self) -> Vec<u8> {
        let mut body = Vec::new();
        for f in &self.fields {
            if let ProtocolField::HttpBodyChunk(chunk) = f {
                body.extend_from_slice(chunk);
            }
        }
        body
    }
}

impl FieldObserver for FieldCache {
    fn on_field(&mut self, field: &ProtocolField) {
        self.fields.push(field.clone());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn field_cache_collects_and_queries() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpStatusCode(200));
        cache.on_field(&ProtocolField::HttpHeader { name: "Content-Type".into(), value: "text/html".into() });
        cache.on_field(&ProtocolField::HttpHeader { name: "Server".into(), value: "nginx".into() });

        assert_eq!(cache.http_status_code(), Some(200));
        assert_eq!(cache.http_header("content-type"), Some("text/html"));
        assert_eq!(cache.http_header("server"), Some("nginx"));
        assert_eq!(cache.http_header("x-missing"), None);
    }

    #[test]
    fn http_header_lookup_is_case_insensitive() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpHeader { name: "X-Frame-Options".into(), value: "DENY".into() });

        assert_eq!(cache.http_header("x-frame-options"), Some("DENY"));
        assert_eq!(cache.http_header("X-FRAME-OPTIONS"), Some("DENY"));
    }

    #[test]
    fn tls_fields() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::TlsAlertCode(40));
        cache.on_field(&ProtocolField::TlsVersion("TLS 1.2".into()));

        assert_eq!(cache.tls_alert_code(), Some(40));
        assert_eq!(cache.tls_version(), Some("TLS 1.2"));
        assert!(!cache.has_tls_server_hello());

        cache.on_field(&ProtocolField::TlsServerHelloSeen);
        assert!(cache.has_tls_server_hello());
    }

    #[test]
    fn redirect_location() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpRedirectLocation("http://blocked.example/".into()));

        assert_eq!(cache.redirect_location(), Some("http://blocked.example/"));
    }

    #[test]
    fn body_bytes_concatenates_chunks() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpBodyChunk(b"hello ".to_vec()));
        cache.on_field(&ProtocolField::HttpBodyChunk(b"world".to_vec()));

        assert_eq!(cache.body_bytes(), b"hello world");
    }

    #[test]
    fn http_headers_returns_all_values() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpHeader { name: "Set-Cookie".into(), value: "a=1".into() });
        cache.on_field(&ProtocolField::HttpHeader { name: "Set-Cookie".into(), value: "b=2".into() });

        let cookies = cache.http_headers("set-cookie");
        assert_eq!(cookies, vec!["a=1", "b=2"]);
    }

    #[test]
    fn empty_cache_returns_none() {
        let cache = FieldCache::new();
        assert_eq!(cache.http_status_code(), None);
        assert_eq!(cache.tls_alert_code(), None);
        assert_eq!(cache.http_header("any"), None);
        assert_eq!(cache.redirect_location(), None);
        assert!(!cache.has_tls_server_hello());
        assert!(cache.body_bytes().is_empty());
    }
}
