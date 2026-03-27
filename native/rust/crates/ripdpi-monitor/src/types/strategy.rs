use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeReport {
    pub suite_id: String,
    pub tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub quic_candidates: Vec<StrategyProbeCandidateSummary>,
    pub recommendation: StrategyProbeRecommendation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub audit_assessment: Option<StrategyProbeAuditAssessment>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeCandidateSummary {
    pub id: String,
    pub label: String,
    pub family: String,
    pub outcome: String,
    pub rationale: String,
    pub succeeded_targets: usize,
    pub total_targets: usize,
    pub weighted_success_score: usize,
    pub total_weight: usize,
    pub quality_score: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proxy_config_json: Option<String>,
    #[serde(default)]
    pub notes: Vec<String>,
    pub average_latency_ms: Option<u64>,
    pub skipped: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeRecommendation {
    pub tcp_candidate_id: String,
    pub tcp_candidate_label: String,
    pub quic_candidate_id: String,
    pub quic_candidate_label: String,
    pub rationale: String,
    pub recommended_proxy_config_json: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeAuditConfidenceLevel {
    High,
    Medium,
    Low,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAuditCoverage {
    pub tcp_candidates_planned: usize,
    pub tcp_candidates_executed: usize,
    pub tcp_candidates_skipped: usize,
    pub tcp_candidates_not_applicable: usize,
    pub quic_candidates_planned: usize,
    pub quic_candidates_executed: usize,
    pub quic_candidates_skipped: usize,
    pub quic_candidates_not_applicable: usize,
    pub tcp_winner_succeeded_targets: usize,
    pub tcp_winner_total_targets: usize,
    pub quic_winner_succeeded_targets: usize,
    pub quic_winner_total_targets: usize,
    pub matrix_coverage_percent: usize,
    pub winner_coverage_percent: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAuditConfidence {
    pub level: StrategyProbeAuditConfidenceLevel,
    pub score: usize,
    pub rationale: String,
    #[serde(default)]
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAuditAssessment {
    pub dns_short_circuited: bool,
    pub coverage: StrategyProbeAuditCoverage,
    pub confidence: StrategyProbeAuditConfidence,
}
