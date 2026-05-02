use serde::{Deserialize, Serialize};

use crate::types::scan::TransportFailureKind;

use super::defaults::transport_failure_none;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QuicProbeStatus {
    InitialResponse,
    Response,
    Empty,
    Error,
    NotRun,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QuicObservationFact {
    pub host: String,
    pub status: QuicProbeStatus,
    #[serde(default = "transport_failure_none")]
    pub transport_failure: TransportFailureKind,
}
