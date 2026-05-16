//! Offline HTTP-injection probe adapter.
//!
//! Wraps `ripdpi-diagnostics-http::classify_http_response` as a [`Probe`]
//! so the runner can drive it through the same trait surface as every other
//! probe. The classification logic itself remains in
//! `ripdpi-diagnostics-http` — this module only adapts the call.
//!
//! Captured response data (status, headers, body) is passed in at
//! construction time; [`ProbeContext`] is unused because the classifier is
//! pure and offline. This is the migration shape for any probe whose
//! "active path" inputs are captured upstream and replayed offline.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_http::http_injection_probe::{classify_http_response, InjectionVerdict};

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Probe identifier embedded in goldens and telemetry. Stable contract.
pub const HTTP_INJECTION_OFFLINE_PROBE_ID: &str = "http_injection_offline";

/// Offline adapter that runs the HTTP injection classifier as a probe.
#[derive(Debug, Clone)]
pub struct HttpInjectionOfflineProbe {
    status: u16,
    headers: Vec<(String, String)>,
    body: String,
}

impl HttpInjectionOfflineProbe {
    /// Construct from captured response data.
    ///
    /// Use `status = 0` to signal "connection reset after request was sent"
    /// (matches the sentinel accepted by `classify_http_response`).
    pub fn new(status: u16, headers: Vec<(String, String)>, body: String) -> Self {
        Self { status, headers, body }
    }
}

impl Probe for HttpInjectionOfflineProbe {
    fn id(&self) -> &'static str {
        HTTP_INJECTION_OFFLINE_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Web
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = match classify_http_response(self.status, &self.headers, &self.body) {
            InjectionVerdict::Clean => ProbeVerdict::Pass,
            InjectionVerdict::Injected(class) => ProbeVerdict::Fail { class },
            InjectionVerdict::RedirectToPortal => ProbeVerdict::Fail { class: "captive-portal-redirect".to_string() },
            InjectionVerdict::ConnectionResetAfterRequest => {
                ProbeVerdict::Fail { class: "tcp-reset-after-request".to_string() }
            }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hdr(name: &str, value: &str) -> (String, String) {
        (name.to_string(), value.to_string())
    }

    #[test]
    fn clean_response_yields_pass() {
        let probe =
            HttpInjectionOfflineProbe::new(200, vec![hdr("content-type", "text/html")], "<html>ok</html>".to_string());
        let outcome = probe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, HTTP_INJECTION_OFFLINE_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Web);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn rkn_body_marker_yields_fail_with_class() {
        let probe = HttpInjectionOfflineProbe::new(
            200,
            vec![hdr("content-type", "text/html; charset=utf-8")],
            "<html>Доступ к ресурсу ограничен</html>".to_string(),
        );
        match probe.run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "ru-rkn-middlebox"),
            other => panic!("expected Fail{{class}}, got {other:?}"),
        }
    }

    #[test]
    fn connection_reset_yields_fail_with_dedicated_class() {
        let probe = HttpInjectionOfflineProbe::new(0, vec![], String::new());
        match probe.run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "tcp-reset-after-request"),
            other => panic!("expected Fail{{class}}, got {other:?}"),
        }
    }
}
