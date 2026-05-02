use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DnsObservationStatus {
    Match,
    ExpectedMismatch,
    CompatibleDivergence,
    SuspiciousDivergence,
    SinkholeSubstitution,
    NxdomainMismatch,
    OracleUnavailable,
    UdpBlocked,
    Unavailable,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DnsObservationFact {
    pub domain: String,
    pub status: DnsObservationStatus,
    #[serde(default)]
    pub udp_addresses: Vec<String>,
    #[serde(default)]
    pub encrypted_addresses: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub udp_latency_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub encrypted_latency_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tampering_score: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub response_anomaly_signals: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cname_targets: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub udp_response_size: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub udp_has_edns0: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub comparison_score: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub record_type_mismatch: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub malformed_pointers: Option<bool>,
    /// Ratio of encrypted_latency / udp_latency * 100 -- high values indicate injection.
    /// Stored as fixed-point (e.g. 4000 means 40.00x ratio).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub injection_latency_ratio: Option<u64>,
    /// UDP-returned IPs that differ from encrypted resolver answers (forged by DPI).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub forged_addresses: Option<Vec<String>>,
    /// When multiple domains share the same forged IP, this marks a middlebox redirect pool.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub forged_address_pool: Option<String>,
}
