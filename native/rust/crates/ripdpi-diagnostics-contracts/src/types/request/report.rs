use std::collections::BTreeMap;

use ripdpi_telemetry::recorder::RecorderSnapshot;
use serde::{Deserialize, Serialize};

use crate::types::{
    ConfirmGoodDpiVerdict, Diagnosis, ExecutionPlanSnapshot, ProbeObservation, ScanCompletionKind, ScanPathMode,
    ScanTerminationReason, StrategyProbeReport,
};

use super::result::ProbeResult;

/// Privacy-safe terminal accounting for candidate runtime cleanup.
///
/// This deliberately exposes only lifecycle counts; it contains no endpoint,
/// address, port, host, or network identifier.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CandidateRuntimeCleanupReceipt {
    pub started: usize,
    pub stopped: usize,
    pub joined: usize,
    pub forced_abort: usize,
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
