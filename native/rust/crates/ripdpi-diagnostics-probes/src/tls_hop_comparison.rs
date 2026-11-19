//! Pure adapter for a captured TLS hop comparison result. The engine owns protected I/O.
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};
use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};
/// Stable identifier for a captured TLS hop comparison result.
pub const TLS_HOP_COMPARISON_PROBE_ID: &str = "tls_hop_comparison_probe";
/// Classify a captured attempt without network I/O.
pub struct TlsHopComparisonProbe {
    result: ProbeResult,
}
impl TlsHopComparisonProbe {
    /// Wrap a captured attempt.
    pub fn new(result: ProbeResult) -> Self {
        Self { result }
    }
}
impl Probe for TlsHopComparisonProbe {
    fn id(&self) -> &'static str {
        TLS_HOP_COMPARISON_PROBE_ID
    }
    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::TlsHopComparison
    }
    fn run(&self, _context: &ProbeContext) -> ProbeOutcome {
        let verdict = if self.result.probe_type == "tls_hop_comparison" && self.result.outcome == "tls_hop_divergence" {
            ProbeVerdict::Pass
        } else {
            ProbeVerdict::Inconclusive { reason: self.result.outcome.clone() }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}
