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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub udp_response: Option<DnsResponseSemantics>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub encrypted_response: Option<DnsResponseSemantics>,
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

/// Query-bound response facts. AD and EDE are resolver claims, not local validation.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DnsResponseSemantics {
    pub query_type: String,
    pub outcome: DnsResponseOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rcode: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ttl_min_seconds: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ttl_max_seconds: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub negative_ttl_seconds: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub truncated: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub authoritative: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recursion_available: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub authenticated_data: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub has_soa: Option<bool>,
    #[serde(default)]
    pub cname_targets: Vec<String>,
    #[serde(default)]
    pub extended_dns_error_codes: Vec<u16>,
}

impl DnsResponseSemantics {
    /// Decode bounded facts from a persisted probe detail; reject invalid metadata.
    pub fn from_detail_json(value: &str) -> Option<Self> {
        if value.len() > 8192 {
            return None;
        }
        let facts: Self = serde_json::from_str(value).ok()?;
        if !matches!(facts.query_type.as_str(), "A" | "AAAA")
            || facts.rcode.is_some_and(|value| value > 4095)
            || facts.cname_targets.len() > 16
            || facts.extended_dns_error_codes.len() > 16
            || facts
                .cname_targets
                .iter()
                .any(|value| value.is_empty() || value.len() > 253 || value.chars().any(char::is_control))
            || matches!((facts.ttl_min_seconds, facts.ttl_max_seconds), (Some(min), Some(max)) if min > max)
        {
            return None;
        }
        Some(facts)
    }

    pub fn to_detail_json(&self) -> Option<String> {
        serde_json::to_string(self).ok()
    }

    pub fn unobserved(query_type: &str, outcome: DnsResponseOutcome) -> Self {
        Self {
            query_type: query_type.to_string(),
            outcome,
            rcode: None,
            ttl_min_seconds: None,
            ttl_max_seconds: None,
            negative_ttl_seconds: None,
            truncated: None,
            authoritative: None,
            recursion_available: None,
            authenticated_data: None,
            has_soa: None,
            cname_targets: Vec::new(),
            extended_dns_error_codes: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DnsResponseOutcome {
    Answer,
    Nodata,
    Nxdomain,
    Servfail,
    Refused,
    OtherRcode,
    Truncated,
    Timeout,
    TransportError,
    Malformed,
    NotObserved,
}

#[cfg(test)]
mod semantics_tests {
    use super::*;

    #[test]
    fn semantic_detail_roundtrip_preserves_unsigned_bounds_and_numeric_ede() {
        let mut value = DnsResponseSemantics::unobserved("AAAA", DnsResponseOutcome::Answer);
        value.rcode = Some(4095);
        value.ttl_min_seconds = Some(0);
        value.ttl_max_seconds = Some(u32::MAX);
        value.extended_dns_error_codes = vec![0, 15, u16::MAX];
        assert_eq!(DnsResponseSemantics::from_detail_json(&value.to_detail_json().unwrap()), Some(value));
    }

    #[test]
    fn semantic_detail_rejects_unbounded_or_inconsistent_metadata() {
        let mut value = DnsResponseSemantics::unobserved("A", DnsResponseOutcome::Nodata);
        value.rcode = Some(4096);
        assert!(DnsResponseSemantics::from_detail_json(&value.to_detail_json().unwrap()).is_none());
        value.rcode = Some(0);
        value.cname_targets = vec!["alias.example".to_string(); 17];
        assert!(DnsResponseSemantics::from_detail_json(&value.to_detail_json().unwrap()).is_none());
        value.cname_targets.clear();
        value.ttl_min_seconds = Some(20);
        value.ttl_max_seconds = Some(10);
        assert!(DnsResponseSemantics::from_detail_json(&value.to_detail_json().unwrap()).is_none());
    }
}
