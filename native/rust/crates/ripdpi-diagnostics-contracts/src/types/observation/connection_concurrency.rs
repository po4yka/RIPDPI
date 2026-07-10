use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnectionConcurrencyCellStatus {
    Healthy,
    Blocked,
    Mixed,
    Contaminated,
    Skipped,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionConcurrencyObservationFact {
    pub cohort_id: String,
    pub tls_profile_id: String,
    pub requested_parallelism: u16,
    pub observed_peak_parallelism: u16,
    pub launch_spread_ms: u32,
    pub burst_window_ms: u32,
    pub successes: u16,
    pub failures: u16,
    #[serde(default)]
    pub block_signals: Vec<String>,
    pub status: ConnectionConcurrencyCellStatus,
    #[serde(default)]
    pub contaminated: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub skip_reason: Option<String>,
}
