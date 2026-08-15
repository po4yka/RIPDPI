use std::time::Duration;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::types::ProbeDetail;

use super::details::detail_entries;
use super::resolver_label::resolver_label;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsOracleTrust {
    TrustedAgreement,
    PrimaryOnly,
    SingleFallback,
    Disagreement,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsOracleTermination {
    QuorumReached,
    CascadeBudgetExhausted,
    Cancelled,
    FallbackLimitReached,
}

impl DnsOracleTermination {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::QuorumReached => "quorum_reached",
            Self::CascadeBudgetExhausted => "cascade_budget_exhausted",
            Self::Cancelled => "cancelled",
            Self::FallbackLimitReached => "fallback_limit_reached",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DnsOracleConfig {
    pub total_budget: Duration,
    pub max_attempt_budget: Duration,
    pub min_attempt_budget: Duration,
}

impl DnsOracleConfig {
    pub const fn new(total_budget: Duration, max_attempt_budget: Duration, min_attempt_budget: Duration) -> Self {
        Self { total_budget, max_attempt_budget, min_attempt_budget }
    }
}

impl Default for DnsOracleConfig {
    fn default() -> Self {
        Self::new(Duration::from_secs(8), Duration::from_secs(4), Duration::from_millis(250))
    }
}

impl DnsOracleTrust {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::TrustedAgreement => "trusted_agreement",
            Self::PrimaryOnly => "primary_only",
            Self::SingleFallback => "single_fallback",
            Self::Disagreement => "disagreement",
            Self::Unavailable => "unavailable",
        }
    }

    pub fn allows_tampering_classification(self) -> bool {
        matches!(self, Self::TrustedAgreement | Self::PrimaryOnly)
    }
}

#[derive(Debug, Clone)]
pub struct DnsOracleResponse {
    pub addresses: Vec<String>,
    pub raw_response: Option<Vec<u8>>,
}

#[derive(Debug, Clone)]
pub struct DnsOracleCandidate<T> {
    pub endpoint: EncryptedDnsEndpoint,
    pub value: T,
    pub answers: Vec<String>,
    pub is_primary: bool,
    pub latency_ms: u128,
}

#[derive(Debug, Clone)]
pub struct DnsOracleAttempt {
    pub resolver_id: String,
    pub is_primary: bool,
    pub latency_ms: u128,
    pub answers: Vec<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone)]
pub struct DnsOracleAssessment<T> {
    pub trust: DnsOracleTrust,
    pub confidence_score: u8,
    pub selected: Option<DnsOracleCandidate<T>>,
    pub agreement_resolver_ids: Vec<String>,
    pub disagreement_resolver_ids: Vec<String>,
    pub attempts: Vec<DnsOracleAttempt>,
    pub termination: DnsOracleTermination,
}

impl<T> DnsOracleAssessment<T> {
    pub fn fallback_resolver_used(&self) -> Option<String> {
        self.selected
            .as_ref()
            .and_then(|candidate| (!candidate.is_primary).then(|| resolver_label(&candidate.endpoint)))
    }

    pub fn selected_latency_ms(&self) -> Option<u128> {
        self.selected.as_ref().map(|candidate| candidate.latency_ms)
    }

    pub fn primary_latency_ms(&self) -> Option<u128> {
        self.attempts.iter().find_map(|attempt| attempt.is_primary.then_some(attempt.latency_ms))
    }

    pub fn preferred_latency_ms(&self) -> u128 {
        self.selected_latency_ms().or_else(|| self.primary_latency_ms()).unwrap_or(0)
    }

    pub fn detail_entries(&self) -> Vec<ProbeDetail> {
        detail_entries(self)
    }
}
