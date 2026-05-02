use serde::{Deserialize, Serialize};

use crate::types::scan::TransportFailureKind;

use super::defaults::{
    endpoint_probe_status_not_run, http_probe_status_not_run, quic_probe_status_not_run, transport_failure_none,
};
use super::{HttpProbeStatus, QuicProbeStatus};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EndpointProbeStatus {
    Ok,
    Failed,
    Blocked,
    NotRun,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ServiceObservationFact {
    pub service: String,
    #[serde(default = "http_probe_status_not_run")]
    pub bootstrap_status: HttpProbeStatus,
    #[serde(default = "http_probe_status_not_run")]
    pub media_status: HttpProbeStatus,
    #[serde(default = "endpoint_probe_status_not_run")]
    pub endpoint_status: EndpointProbeStatus,
    #[serde(default = "transport_failure_none")]
    pub endpoint_failure: TransportFailureKind,
    #[serde(default = "quic_probe_status_not_run")]
    pub quic_status: QuicProbeStatus,
    #[serde(default = "transport_failure_none")]
    pub quic_failure: TransportFailureKind,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CircumventionObservationFact {
    pub tool: String,
    #[serde(default = "http_probe_status_not_run")]
    pub bootstrap_status: HttpProbeStatus,
    #[serde(default = "endpoint_probe_status_not_run")]
    pub handshake_status: EndpointProbeStatus,
    #[serde(default = "transport_failure_none")]
    pub handshake_failure: TransportFailureKind,
}
