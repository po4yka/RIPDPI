//! QUIC-probe outcome classifier wrapped as a Probe.
//!
//! Adapts `ripdpi_failure_classifier::classify_quic_probe` to the
//! [`Probe`] trait. The upstream classifier takes a string outcome
//! tag (matching the diagnostics runner's QUIC-probe contract) and
//! an optional error string; healthy outcomes return None, anything
//! else becomes a [`ClassifiedFailure`] with class QuicBreakage.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_failure_classifier::classify_quic_probe;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const QUIC_PROBE_OFFLINE_PROBE_ID: &str = "quic_probe_offline";

/// Wraps the offline QUIC-probe outcome classifier over a captured
/// outcome string and an optional error string.
#[derive(Debug, Clone)]
pub struct QuicProbeOfflineProbe {
    outcome: String,
    error: Option<String>,
}

impl QuicProbeOfflineProbe {
    /// Construct from an upstream-captured QUIC probe outcome tag
    /// (e.g. `"quic_initial_response"`, `"timeout"`) and the optional
    /// raw error string the dialer surfaced.
    pub fn new(outcome: impl Into<String>, error: Option<String>) -> Self {
        Self { outcome: outcome.into(), error }
    }
}

impl Probe for QuicProbeOfflineProbe {
    fn id(&self) -> &'static str {
        QUIC_PROBE_OFFLINE_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Quic
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = match classify_quic_probe(&self.outcome, self.error.as_deref()) {
            None => ProbeVerdict::Pass,
            Some(failure) => {
                let outcome_tag =
                    failure.evidence.tags.iter().find_map(|t| t.strip_prefix("outcome=")).unwrap_or("unknown");
                ProbeVerdict::Fail { class: format!("quic-breakage::{outcome_tag}") }
            }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn healthy_initial_response_yields_pass() {
        let outcome = QuicProbeOfflineProbe::new("quic_initial_response", None).run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, QUIC_PROBE_OFFLINE_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Quic);
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn healthy_response_yields_pass() {
        let outcome = QuicProbeOfflineProbe::new("quic_response", None).run(&ProbeContext::empty());
        assert_eq!(outcome.verdict, ProbeVerdict::Pass);
    }

    #[test]
    fn timeout_outcome_carries_outcome_in_class() {
        match QuicProbeOfflineProbe::new("timeout", Some("deadline".to_string())).run(&ProbeContext::empty()).verdict {
            ProbeVerdict::Fail { class } => assert_eq!(class, "quic-breakage::timeout"),
            other => panic!("expected Fail, got {other:?}"),
        }
    }
}
