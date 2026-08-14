mod conversions;
mod progress_wire;
mod report_wire;
mod request_wire;
mod resolver_recommendation;

#[cfg(test)]
mod tests;

use crate::types::{DiagnosticProfileFamily, ProbeObservation, ProbeTask, ProbeTaskFamily, ScanKind};

pub use progress_wire::EngineProgressWire;
pub use report_wire::{EngineProbeResultWire, EngineScanReportWire};
pub use request_wire::EngineScanRequestWire;
pub use resolver_recommendation::ResolverRecommendationWire;

// v6: adds the planned execution inventory to completed and partial reports.
pub const DIAGNOSTICS_ENGINE_SCHEMA_VERSION: u32 = 6;

pub type EngineProbeTaskFamily = ProbeTaskFamily;
pub type EngineProbeTaskWire = ProbeTask;
pub type EngineObservationWire = ProbeObservation;

fn default_scan_kind() -> ScanKind {
    ScanKind::Connectivity
}

fn default_diagnostic_profile_family() -> DiagnosticProfileFamily {
    DiagnosticProfileFamily::General
}
