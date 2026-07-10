use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConfirmGoodDpiEvidenceSource {
    Active,
    Passive,
    Mixed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfirmGoodDpiEvidence {
    pub source: ConfirmGoodDpiEvidenceSource,
    pub stalled_flow_count: usize,
    pub distinct_target_count: usize,
    pub catalog_profile_validated: bool,
    pub reality_handshake_confirmed: bool,
    pub application_response_bytes: u64,
    #[serde(default)]
    pub quic_control_succeeded: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConfirmGoodDpiVerdictStatus {
    Suspected,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfirmGoodDpiVerdict {
    pub status: ConfirmGoodDpiVerdictStatus,
    pub evidence: ConfirmGoodDpiEvidence,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransportFamily {
    UdpQuic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransportPivotViability {
    Confirmed,
    Unknown,
    Blocked,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransportPivotRecommendation {
    pub reason_code: String,
    pub preferred_family: TransportFamily,
    pub viability: TransportPivotViability,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub selected_relay_role: Option<String>,
}
