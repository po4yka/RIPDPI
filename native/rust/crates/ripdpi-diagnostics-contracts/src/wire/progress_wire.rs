use serde::{Deserialize, Serialize};

use crate::types::StrategyProbeLiveProgress;

use super::default_schema_version;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineProgressWire {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub session_id: String,
    pub phase: String,
    pub completed_steps: usize,
    pub total_steps: usize,
    pub message: String,
    #[serde(default)]
    pub is_finished: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_outcome: Option<String>,
    #[serde(default)]
    pub strategy_probe_progress: Option<StrategyProbeLiveProgress>,
}
