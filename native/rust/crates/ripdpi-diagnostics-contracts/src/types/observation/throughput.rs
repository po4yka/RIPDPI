use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ThroughputProbeStatus {
    Measured,
    HttpUnreachable,
    InvalidTarget,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ThroughputObservationFact {
    pub label: String,
    pub status: ThroughputProbeStatus,
    #[serde(default)]
    pub is_control: bool,
    #[serde(default)]
    pub median_bps: u64,
    #[serde(default)]
    pub sample_bps: Vec<u64>,
    #[serde(default)]
    pub window_bytes: usize,
}
