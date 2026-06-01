use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::types::{Diagnosis, ProbeDetail, ScanPathMode, StrategyProbeReport};

use super::{EngineObservationWire, ResolverRecommendationWire, default_schema_version};

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
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub session_id: String,
    pub profile_id: String,
    pub path_mode: ScanPathMode,
    pub started_at: u64,
    pub finished_at: u64,
    pub summary: String,
    #[serde(default)]
    pub results: Vec<EngineProbeResultWire>,
    #[serde(default)]
    pub resolver_recommendation: Option<ResolverRecommendationWire>,
    #[serde(default)]
    pub strategy_probe_report: Option<StrategyProbeReport>,
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
}
