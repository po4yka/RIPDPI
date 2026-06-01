use serde::{Deserialize, Serialize};

use crate::types::scan::TransportFailureKind;

use super::TlsProbeStatus;
use super::defaults::{strategy_probe_protocol_candidate, strategy_probe_status_failed, tls_probe_status_not_run};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeProtocol {
    Http,
    Https,
    Quic,
    Candidate,
    Baseline,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeStatus {
    Success,
    Partial,
    Failed,
    Skipped,
    NotApplicable,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyObservationFact {
    #[serde(default)]
    pub candidate_id: Option<String>,
    #[serde(default)]
    pub candidate_label: Option<String>,
    #[serde(default)]
    pub candidate_family: Option<String>,
    #[serde(default = "strategy_probe_protocol_candidate")]
    pub protocol: StrategyProbeProtocol,
    #[serde(default = "strategy_probe_status_failed")]
    pub status: StrategyProbeStatus,
    #[serde(default = "tls_probe_status_not_run")]
    pub tls_ech_status: TlsProbeStatus,
    #[serde(default)]
    pub tls_ech_version: Option<String>,
    #[serde(default)]
    pub tls_ech_error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_ech_resolution_detail: Option<String>,
    #[serde(default = "super::defaults::transport_failure_none")]
    pub transport_failure: TransportFailureKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_error: Option<String>,
    #[serde(default)]
    pub h3_advertised: bool,
}
