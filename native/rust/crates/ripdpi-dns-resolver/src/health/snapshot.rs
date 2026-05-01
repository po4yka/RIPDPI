#[derive(Debug, Clone)]
pub struct HealthScoreSnapshot {
    pub ewma_success_rate: f64,
    pub ewma_latency_ms: f64,
    pub ewma_oracle_score: f64,
    pub observation_count: u64,
    pub oracle_observation_count: u64,
    pub oracle_disagreement_streak: u32,
    pub quarantined: bool,
}
