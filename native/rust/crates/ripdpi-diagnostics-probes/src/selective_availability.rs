//! Pure adapter for a captured matrix attempt. The engine owns protected I/O.
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};
use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};
/// Stable identifier for a captured matrix attempt.
pub const SELECTIVE_AVAILABILITY_PROBE_ID: &str = "selective_availability_probe";
/// Classify a captured attempt without network I/O.
pub struct SelectiveAvailabilityProbe {
    result: ProbeResult,
}
impl SelectiveAvailabilityProbe {
    /// Wrap a captured attempt.
    pub fn new(result: ProbeResult) -> Self {
        Self { result }
    }
}
impl Probe for SelectiveAvailabilityProbe {
    fn id(&self) -> &'static str {
        SELECTIVE_AVAILABILITY_PROBE_ID
    }
    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::SelectiveAvailability
    }
    fn run(&self, _context: &ProbeContext) -> ProbeOutcome {
        let verdict =
            if self.result.probe_type == "selective_availability" && self.result.outcome == "matrix_target_available" {
                ProbeVerdict::Pass
            } else {
                ProbeVerdict::Inconclusive { reason: self.result.outcome.clone() }
            };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}
