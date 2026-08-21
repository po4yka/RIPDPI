use std::collections::BTreeMap;

use ripdpi_telemetry::recorder::RecorderSnapshot;
use serde::{Deserialize, Serialize};

use crate::types::{
    ConfirmGoodDpiVerdict, Diagnosis, ExecutionPlanSnapshot, ProbeObservation, ScanCompletionKind, ScanPathMode,
    ScanTerminationReason, StrategyProbeProgressLane, StrategyProbeReport,
};

use super::result::ProbeResult;

/// Privacy-safe terminal accounting for candidate runtime cleanup.
///
/// This deliberately exposes only lifecycle counts; it contains no endpoint,
/// address, port, host, or network identifier.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CandidateRuntimeCleanupReceipt {
    pub started: usize,
    pub stopped: usize,
    pub joined: usize,
    pub forced_abort: usize,
    #[serde(default)]
    pub candidates: Vec<CandidateRuntimeCleanupDetail>,
    #[serde(default)]
    pub duplicate_refusal_count: usize,
    #[serde(default)]
    pub address_attempt_count: usize,
    #[serde(default)]
    pub connection_refused_count: usize,
    #[serde(default)]
    pub drain_latency_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cleanup_outcome: Option<CandidateRuntimeCleanupOutcome>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CandidateRuntimeCleanupDetail {
    pub scan_session_id: String,
    pub candidate_id: String,
    pub lane: StrategyProbeProgressLane,
    /// A process-local keyed alias; never a raw target or stable identifier.
    #[serde(skip)]
    pub trial_alias: String,
    pub outcome: CandidateRuntimeCleanupOutcome,
    pub address_attempt_count: usize,
    pub connection_refused_count: usize,
    pub duplicate_refusal_count: usize,
    pub drain_latency_ms: u64,
    pub forced_abort: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CandidateRuntimeCleanupOutcome {
    Graceful,
    Cancelled,
    Deadline,
    Aborted,
    JoinFailed,
    RuntimeFailed,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanReportDisposition {
    #[default]
    Terminal,
    Checkpoint,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanReport {
    pub session_id: String,
    pub profile_id: String,
    pub path_mode: ScanPathMode,
    pub started_at: u64,
    pub finished_at: u64,
    pub summary: String,
    #[serde(default)]
    pub report_disposition: ScanReportDisposition,
    #[serde(default, skip_serializing_if = "ScanCompletionKind::is_normal")]
    pub completion_kind: ScanCompletionKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub termination_reason: Option<ScanTerminationReason>,
    pub results: Vec<ProbeResult>,
    #[serde(default)]
    pub observations: Vec<ProbeObservation>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub engine_analysis_version: Option<String>,
    #[serde(default)]
    pub diagnoses: Vec<Diagnosis>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classifier_version: Option<String>,
    #[serde(default)]
    pub pack_versions: BTreeMap<String, u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_report: Option<StrategyProbeReport>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirm_good_dpi_verdict: Option<ConfirmGoodDpiVerdict>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metrics_summary: Option<RecorderSnapshot>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_plan: Option<ExecutionPlanSnapshot>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub candidate_runtime_cleanup: Option<CandidateRuntimeCleanupReceipt>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidate_cleanup_ledger_serializes_exact_counts_without_raw_hostname() {
        let receipt = CandidateRuntimeCleanupReceipt {
            started: 1,
            stopped: 1,
            joined: 0,
            forced_abort: 1,
            candidates: vec![CandidateRuntimeCleanupDetail {
                scan_session_id: "scan-1".to_string(),
                candidate_id: "candidate-1".to_string(),
                lane: StrategyProbeProgressLane::Tcp,
                trial_alias: "ephemeral".to_string(),
                outcome: CandidateRuntimeCleanupOutcome::Aborted,
                address_attempt_count: 2,
                connection_refused_count: 2,
                duplicate_refusal_count: 1,
                drain_latency_ms: 1000,
                forced_abort: true,
            }],
            duplicate_refusal_count: 1,
            address_attempt_count: 2,
            connection_refused_count: 2,
            drain_latency_ms: 1000,
            cleanup_outcome: Some(CandidateRuntimeCleanupOutcome::Aborted),
        };

        let json = serde_json::to_string(&receipt).expect("serialize cleanup ledger");
        assert!(json.contains("connectionRefusedCount"));
        assert!(json.contains("duplicateRefusalCount"));
        assert!(!json.contains("example.com"));
        assert!(!json.contains("ephemeral"));
    }
}
