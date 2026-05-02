use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanProgress {
    pub session_id: String,
    pub phase: String,
    pub completed_steps: usize,
    pub total_steps: usize,
    pub message: String,
    pub is_finished: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_outcome: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_progress: Option<StrategyProbeLiveProgress>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeProgressLane {
    Tcp,
    Quic,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeLiveProgress {
    pub lane: StrategyProbeProgressLane,
    pub candidate_index: usize,
    pub candidate_total: usize,
    pub candidate_id: String,
    pub candidate_label: String,
    #[serde(default)]
    pub succeeded_targets: usize,
    #[serde(default)]
    pub total_targets: usize,
}
