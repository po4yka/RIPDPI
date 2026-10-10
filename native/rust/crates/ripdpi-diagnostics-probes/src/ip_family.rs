//! Pure adapter for a captured IP family result. The engine owns protected I/O.
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};
use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};
/// Stable identifier for a captured IP family result.
pub const IP_FAMILY_PROBE_ID: &str = "ip_family_probe";
/// Classify a captured attempt without network I/O.
pub struct IpFamilyProbe {
    result: ProbeResult,
}
impl IpFamilyProbe {
    /// Wrap a captured attempt.
    pub fn new(result: ProbeResult) -> Self {
        Self { result }
    }
}
impl Probe for IpFamilyProbe {
    fn id(&self) -> &'static str {
        IP_FAMILY_PROBE_ID
    }
    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::IpFamily
    }
    fn run(&self, _context: &ProbeContext) -> ProbeOutcome {
        let verdict = if self.result.probe_type == "ip_family" && self.result.outcome == "ip_family_reachable" {
            ProbeVerdict::Pass
        } else {
            ProbeVerdict::Inconclusive { reason: self.result.outcome.clone() }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}
