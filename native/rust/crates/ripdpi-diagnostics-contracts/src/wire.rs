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

// v2: added the `DOH_JSON_SURVEY` probe family + engine stage (DoH-JSON
// resolver survey). Wire is backward-additive (new probe_type string + family
// variant); the bump signals the new stage to schema-version-pinned consumers.
pub const DIAGNOSTICS_ENGINE_SCHEMA_VERSION: u32 = 2;

pub type EngineProbeTaskFamily = ProbeTaskFamily;
pub type EngineProbeTaskWire = ProbeTask;
pub type EngineObservationWire = ProbeObservation;

fn default_schema_version() -> u32 {
    DIAGNOSTICS_ENGINE_SCHEMA_VERSION
}

fn default_scan_kind() -> ScanKind {
    ScanKind::Connectivity
}

fn default_diagnostic_profile_family() -> DiagnosticProfileFamily {
    DiagnosticProfileFamily::General
}
