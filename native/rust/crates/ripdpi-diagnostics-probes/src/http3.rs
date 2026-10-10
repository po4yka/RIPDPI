//! Pure adapter for a captured HTTP/3 result. The engine owns protected I/O.
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};
use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};
/// Stable identifier for a captured HTTP/3 result.
pub const HTTP3_PROBE_ID: &str = "http3_probe";
/// Classify a captured attempt without network I/O.
pub struct Http3Probe {
    result: ProbeResult,
}
impl Http3Probe {
    /// Wrap a captured attempt.
    pub fn new(result: ProbeResult) -> Self {
        Self { result }
    }
}
impl Probe for Http3Probe {
    fn id(&self) -> &'static str {
        HTTP3_PROBE_ID
    }
    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Http3
    }
    fn run(&self, _context: &ProbeContext) -> ProbeOutcome {
        let verdict = if self.result.probe_type == "http3" && self.result.outcome == "http3_complete" {
            ProbeVerdict::Pass
        } else {
            ProbeVerdict::Inconclusive { reason: self.result.outcome.clone() }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}
