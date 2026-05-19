mod analytics;

use crate::classification::pack_versions_from_refs;
use crate::observations::ENGINE_ANALYSIS_VERSION;
use crate::types::{ProbeObservation, ProbeResult, ScanReport, ScanRequest, StrategyProbeReport};
use ripdpi_telemetry::recorder;

pub(super) use analytics::{connectivity_analytics_summary, connectivity_summary};

pub(super) fn build_report(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    summary: String,
    results: Vec<ProbeResult>,
    observations: Vec<ProbeObservation>,
    strategy_probe_report: Option<StrategyProbeReport>,
    classifier_version: Option<String>,
) -> ScanReport {
    ScanReport {
        session_id,
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: crate::util::now_ms(),
        summary,
        results,
        observations,
        engine_analysis_version: Some(ENGINE_ANALYSIS_VERSION.to_string()),
        diagnoses: Vec::new(),
        classifier_version,
        pack_versions: pack_versions_from_refs(&request.pack_refs),
        strategy_probe_report,
        metrics_summary: recorder::snapshot(),
    }
}
