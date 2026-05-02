use std::collections::BTreeMap;

use ripdpi_telemetry::recorder::RecorderSnapshot;
use serde::{Deserialize, Serialize};

use crate::types::{Diagnosis, ProbeObservation, ScanPathMode, StrategyProbeReport};

use super::result::ProbeResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanReport {
    pub session_id: String,
    pub profile_id: String,
    pub path_mode: ScanPathMode,
    pub started_at: u64,
    pub finished_at: u64,
    pub summary: String,
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
    pub metrics_summary: Option<RecorderSnapshot>,
}
