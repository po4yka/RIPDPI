//! Offline TLS-alert classifier wrapped as a Probe.
//!
//! Adapts `ripdpi_failure_classifier::classify_tls_alert` to the
//! [`Probe`] trait. The classifier inspects a captured first-response
//! buffer; if the bytes look like a TLS alert record, it emits a
//! ClassifiedFailure carrying the alert description.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_failure_classifier::classify_tls_alert;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const TLS_ALERT_OFFLINE_PROBE_ID: &str = "tls_alert_offline";

/// Wraps the offline TLS-alert classifier over a captured response buffer.
#[derive(Debug, Clone)]
pub struct TlsAlertOfflineProbe {
    response: Vec<u8>,
}

impl TlsAlertOfflineProbe {
    /// Construct from a captured first-response buffer.
    pub fn new(response: Vec<u8>) -> Self {
        Self { response }
    }
}

impl Probe for TlsAlertOfflineProbe {
    fn id(&self) -> &'static str {
        TLS_ALERT_OFFLINE_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        // ProbeTaskFamily has no dedicated TLS variant; Web (HTTPS family)
        // is the closest umbrella for TLS-side findings emitted by the
        // diagnostics runner.
        ProbeTaskFamily::Web
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = match classify_tls_alert(&self.response) {
            None => ProbeVerdict::Pass,
            Some(failure) => {
                let class = match failure.evidence.tags.iter().find_map(|t| t.strip_prefix("alert=")) {
                    Some(s) => format!("tls-alert::{s}"),
                    None => "tls-alert".to_string(),
                };
                ProbeVerdict::Fail { class }
            }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_response_yields_pass() {
        let outcome = TlsAlertOfflineProbe::new(Vec::new()).run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, TLS_ALERT_OFFLINE_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Web);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn non_alert_record_yields_pass() {
        // TLS handshake record (0x16) is not a TLS alert (0x15).
        let outcome =
            TlsAlertOfflineProbe::new(vec![0x16, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00]).run(&ProbeContext::empty());
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn alert_record_with_handshake_failure_yields_fail() {
        // TLS alert record: content_type=0x15, version, length=2,
        // level=2 (fatal), description=0x28 (handshake_failure).
        let alert = vec![0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];
        match TlsAlertOfflineProbe::new(alert).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert!(class.starts_with("tls-alert"), "{class:?}"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }
}
