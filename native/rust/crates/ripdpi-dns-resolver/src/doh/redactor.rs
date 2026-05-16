//! `DohRedactor` — strips privacy-sensitive fields from DoH log events.
//!
//! In release builds the following are never emitted to logs or diagnostics:
//! - The full DoH URL path (including query parameters which may contain `dns=`)
//! - Raw request body bytes
//! - Domain names extracted from the DNS question section
//! - Raw response payload bytes

/// Redacts privacy-sensitive fields from DoH diagnostic events.
///
/// All methods return a fixed placeholder string so that:
/// 1. Code paths that format log lines are never conditionally compiled out —
///    the redaction happens at the *value* level, not at the call site.
/// 2. Tests can assert that redacted output does not contain real values.
#[derive(Debug, Clone, Default)]
pub struct DohRedactor;

impl DohRedactor {
    pub fn new() -> Self {
        Self
    }

    /// Returns a safe placeholder for the DoH request URL (path + query).
    ///
    /// The full URL path must not be logged because in GET mode the `dns=`
    /// parameter encodes the DNS question in base64url, leaking the queried
    /// domain.
    pub fn redact_url_path(&self, _url_path: &str) -> &'static str {
        "<url-path redacted>"
    }

    /// Returns a safe placeholder for the raw request body bytes.
    pub fn redact_request_body(&self, _body: &[u8]) -> &'static str {
        "<request-body redacted>"
    }

    /// Returns a safe placeholder for a domain name extracted from the DNS
    /// question section.
    pub fn redact_domain(&self, _domain: &str) -> &'static str {
        "<domain redacted>"
    }

    /// Returns a safe placeholder for the raw response payload bytes.
    pub fn redact_response_payload(&self, _payload: &[u8]) -> &'static str {
        "<response-payload redacted>"
    }
}
