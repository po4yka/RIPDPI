//! Offline HTTP-response-block classifier wrapped as a Probe.
//!
//! Adapts `ripdpi_failure_classifier::classify_http_response_block` to
//! the [`Probe`] trait. Complements [`crate::HttpInjectionOfflineProbe`]:
//! HttpInjectionOfflineProbe handles structured-field input (status,
//! headers, body), while this probe consumes the raw response bytes
//! that the runner captured upstream and dispatches to the bundled
//! blockpage fingerprint table inside `ripdpi-failure-classifier`.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_failure_classifier::{FailureClass, classify_http_response_block};

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const HTTP_RESPONSE_BLOCK_OFFLINE_PROBE_ID: &str = "http_response_block_offline";

/// Wraps the offline HTTP-blockpage classifier over a captured raw response.
#[derive(Debug, Clone)]
pub struct HttpResponseBlockOfflineProbe {
    response: Vec<u8>,
}

impl HttpResponseBlockOfflineProbe {
    /// Construct from a captured raw HTTP response (status line + headers
    /// + body, CRLF-delimited).
    pub fn new(response: Vec<u8>) -> Self {
        Self { response }
    }
}

fn failure_class_id(class: FailureClass) -> &'static str {
    match class {
        FailureClass::HttpBlockpage => "http-blockpage",
        FailureClass::Redirect => "http-redirect-to-block",
        _ => "http-block-other",
    }
}

impl Probe for HttpResponseBlockOfflineProbe {
    fn id(&self) -> &'static str {
        HTTP_RESPONSE_BLOCK_OFFLINE_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Web
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = match classify_http_response_block(&self.response) {
            None => ProbeVerdict::Pass,
            Some(failure) => {
                let provider = failure.evidence.tags.iter().find_map(|t| t.strip_prefix("provider=")).unwrap_or("");
                let base = failure_class_id(failure.class);
                let class = if provider.is_empty() { base.to_string() } else { format!("{base}::{provider}") };
                ProbeVerdict::Fail { class }
            }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build_http_response(status_line: &str, headers: &[(&str, &str)], body: &str) -> Vec<u8> {
        let mut buf = format!("{status_line}\r\n");
        for (name, value) in headers {
            buf.push_str(name);
            buf.push_str(": ");
            buf.push_str(value);
            buf.push_str("\r\n");
        }
        buf.push_str("\r\n");
        buf.push_str(body);
        buf.into_bytes()
    }

    #[test]
    fn clean_200_yields_pass() {
        let response = build_http_response("HTTP/1.1 200 OK", &[("Content-Type", "text/html")], "<html/>");
        let outcome = HttpResponseBlockOfflineProbe::new(response).run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, HTTP_RESPONSE_BLOCK_OFFLINE_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Web);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn rate_limit_429_returns_pass() {
        let response = build_http_response("HTTP/1.1 429 Too Many Requests", &[], "rate limited");
        let outcome = HttpResponseBlockOfflineProbe::new(response).run(&ProbeContext::empty());
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn empty_response_yields_pass() {
        let outcome = HttpResponseBlockOfflineProbe::new(Vec::new()).run(&ProbeContext::empty());
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }
}
