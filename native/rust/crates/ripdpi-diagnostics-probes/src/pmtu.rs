//! Pure adapter for a captured PMTU result. The engine owns protected I/O.
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};
use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};
/// Stable identifier for a captured PMTU result.
pub const PMTU_PROBE_ID: &str = "pmtu_probe";
/// Classify a captured attempt without network I/O.
pub struct PmtuProbe {
    result: ProbeResult,
}
impl PmtuProbe {
    /// Wrap a captured attempt.
    pub fn new(result: ProbeResult) -> Self {
        Self { result }
    }
}
impl Probe for PmtuProbe {
    fn id(&self) -> &'static str {
        PMTU_PROBE_ID
    }
    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Pmtu
    }
    fn run(&self, _context: &ProbeContext) -> ProbeOutcome {
        let verdict = if self.result.probe_type == "pmtu" && self.result.outcome == "pmtu_observed" {
            ProbeVerdict::Pass
        } else {
            ProbeVerdict::Inconclusive { reason: self.result.outcome.clone() }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}
