//! Classifies recorded QUIC Initial probe outcomes without attributing network failures.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

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
        let verdict = if self.outcome == "quic_initial_response" {
            ProbeVerdict::Pass
        } else {
            let reason = self.error.as_deref().filter(|error| !error.is_empty()).unwrap_or(&self.outcome);
            ProbeVerdict::Inconclusive { reason: reason.to_string() }
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
    fn outcomes_without_known_initial_crypto_are_inconclusive() {
        for status in ["quic_response", "quic_retry", "quic_timeout", "capability_skipped", "unknown"] {
            let outcome = QuicProbeOfflineProbe::new(status, None).run(&ProbeContext::empty());
            assert_eq!(outcome.verdict, ProbeVerdict::Inconclusive { reason: status.to_string() });
        }
    }
}
