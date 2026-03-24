use crate::classification::pack_versions_from_refs;
use crate::observations::ENGINE_ANALYSIS_VERSION;
use crate::types::{ProbeObservation, ProbeResult, ScanReport, ScanRequest, StrategyProbeReport};
use crate::util::classify_probe_outcome;
use ripdpi_telemetry::recorder;

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

pub(super) fn connectivity_summary(results: &[ProbeResult], path_mode: &crate::types::ScanPathMode) -> String {
    let mut healthy = 0usize;
    let mut attention = 0usize;
    let mut failed = 0usize;
    let mut inconclusive = 0usize;

    for result in results {
        match classify_probe_outcome(&result.probe_type, path_mode, &result.outcome).bucket {
            crate::util::ProbeOutcomeBucket::Healthy => healthy += 1,
            crate::util::ProbeOutcomeBucket::Attention => attention += 1,
            crate::util::ProbeOutcomeBucket::Failed => failed += 1,
            crate::util::ProbeOutcomeBucket::Inconclusive => inconclusive += 1,
        }
    }

    let mut parts = vec![format!("{} completed", results.len()), format!("{healthy} healthy")];
    if attention > 0 {
        parts.push(format!("{attention} attention"));
    }
    if failed > 0 {
        parts.push(format!("{failed} failed"));
    }
    if inconclusive > 0 {
        parts.push(format!("{inconclusive} inconclusive"));
    }
    parts.join(" · ")
}
