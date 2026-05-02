use serde::{Deserialize, Serialize};

use crate::types::scan::ObservationKind;

use super::{
    CircumventionObservationFact, DnsObservationFact, DomainObservationFact, QuicObservationFact,
    ServiceObservationFact, StrategyObservationFact, TcpObservationFact, TelegramObservationFact,
    ThroughputObservationFact,
};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProbeObservation {
    pub kind: ObservationKind,
    pub target: String,
    #[serde(default)]
    pub dns: Option<DnsObservationFact>,
    #[serde(default)]
    pub domain: Option<DomainObservationFact>,
    #[serde(default)]
    pub tcp: Option<TcpObservationFact>,
    #[serde(default)]
    pub quic: Option<QuicObservationFact>,
    #[serde(default)]
    pub service: Option<ServiceObservationFact>,
    #[serde(default)]
    pub circumvention: Option<CircumventionObservationFact>,
    #[serde(default)]
    pub telegram: Option<TelegramObservationFact>,
    #[serde(default)]
    pub throughput: Option<ThroughputObservationFact>,
    #[serde(default)]
    pub strategy: Option<StrategyObservationFact>,
    #[serde(default)]
    pub evidence: Vec<String>,
}
