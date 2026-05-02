use serde::{Deserialize, Serialize};

use crate::types::scan::TransportFailureKind;

use super::defaults::{http_probe_status_not_run, tls_probe_status_not_run, transport_failure_none};
use super::{HttpProbeStatus, TlsProbeStatus};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DomainObservationFact {
    pub host: String,
    #[serde(default = "http_probe_status_not_run")]
    pub http_status: HttpProbeStatus,
    #[serde(default = "tls_probe_status_not_run")]
    pub tls13_status: TlsProbeStatus,
    #[serde(default = "tls_probe_status_not_run")]
    pub tls12_status: TlsProbeStatus,
    #[serde(default = "tls_probe_status_not_run")]
    pub tls_ech_status: TlsProbeStatus,
    #[serde(default)]
    pub tls_ech_version: Option<String>,
    #[serde(default)]
    pub tls_ech_error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_ech_resolution_detail: Option<String>,
    #[serde(default = "transport_failure_none")]
    pub transport_failure: TransportFailureKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_error: Option<String>,
    #[serde(default)]
    pub certificate_anomaly: bool,
    #[serde(default)]
    pub is_control: bool,
    #[serde(default)]
    pub h3_advertised: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt_svc: Option<String>,
}
