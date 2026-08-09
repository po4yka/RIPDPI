use serde::{Deserialize, Serialize};

use crate::types::StrategyProbeProgressLane;

pub const STRATEGY_PROBE_ATTEMPT_VERSION: &str = "strategy_attempt_v1";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeAttemptStatus {
    Planned,
    Executed,
    Skipped,
    NotApplicable,
    Eliminated,
    TimedOut,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeAttemptRound {
    Baseline,
    Qualifier,
    FullMatrix,
    NotStarted,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAttempt {
    pub attempt_version: String,
    pub sequence: usize,
    pub candidate_index: usize,
    pub candidate_id: String,
    pub candidate_label: String,
    pub candidate_family: String,
    pub lane: StrategyProbeProgressLane,
    pub target: String,
    pub target_index: usize,
    pub is_control: bool,
    pub protocol: String,
    pub round: StrategyProbeAttemptRound,
    pub status: StrategyProbeAttemptStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub started_at_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(default)]
    pub retry_count: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}
