use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::types::{
    ConfirmGoodDpiVerdict, Diagnosis, ExecutionPlanSnapshot, ProbeDetail, ScanCompletionKind, ScanPathMode,
    ScanTerminationReason, StrategyProbeReport,
};

use super::{EngineObservationWire, ResolverRecommendationWire};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineProbeResultWire {
    pub probe_type: String,
    pub target: String,
    pub outcome: String,
    #[serde(default)]
    pub details: Vec<ProbeDetail>,
    #[serde(default)]
    pub probe_retry_count: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineScanReportWire {
    pub schema_version: u32,
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
    #[serde(default)]
    pub results: Vec<EngineProbeResultWire>,
    #[serde(default)]
    pub resolver_recommendation: Option<ResolverRecommendationWire>,
    #[serde(default)]
    pub strategy_probe_report: Option<StrategyProbeReport>,
    #[serde(default)]
    pub confirm_good_dpi_verdict: Option<ConfirmGoodDpiVerdict>,
    #[serde(default)]
    pub observations: Vec<EngineObservationWire>,
    #[serde(default)]
    pub engine_analysis_version: Option<String>,
    #[serde(default)]
    pub diagnoses: Vec<Diagnosis>,
    #[serde(default)]
    pub classifier_version: Option<String>,
    #[serde(default)]
    pub pack_versions: BTreeMap<String, u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_plan: Option<ExecutionPlanSnapshot>,
}
