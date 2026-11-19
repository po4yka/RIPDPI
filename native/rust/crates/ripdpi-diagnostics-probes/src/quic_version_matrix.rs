//! Pure adapter for a captured QUIC version matrix row. The engine owns protected I/O.
use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};
use ripdpi_diagnostics_contracts::{ProbeResult, ProbeTaskFamily};
/// Stable identifier for a captured QUIC version matrix row.
pub const QUIC_VERSION_MATRIX_PROBE_ID: &str = "quic_version_matrix_probe";
/// Classify a captured row without network I/O.
pub struct QuicVersionMatrixProbe {
    result: ProbeResult,
}
impl QuicVersionMatrixProbe {
    /// Wrap a captured row.
    pub fn new(result: ProbeResult) -> Self {
        Self { result }
    }
}
impl Probe for QuicVersionMatrixProbe {
    fn id(&self) -> &'static str {
        QUIC_VERSION_MATRIX_PROBE_ID
    }
    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::QuicVersionMatrix
    }
    fn run(&self, _context: &ProbeContext) -> ProbeOutcome {
        // Only an Initial-stage version difference passes; every other row says nothing about blocking.
        let verdict = if self.result.probe_type == "quic_version_comparison"
            && self.result.outcome == "quic_version_difference_observed"
        {
            ProbeVerdict::Pass
        } else {
            ProbeVerdict::Inconclusive { reason: self.result.outcome.clone() }
        };
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}
