use serde::{Deserialize, Serialize};

use super::scan::{ObservationKind, TransportFailureKind};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DnsObservationStatus {
    Match,
    ExpectedMismatch,
    Substitution,
    Nxdomain,
    EncryptedBlocked,
    UdpBlocked,
    Unavailable,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HttpProbeStatus {
    Ok,
    Blockpage,
    Unreachable,
    NotRun,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TlsProbeStatus {
    Ok,
    HandshakeFailed,
    VersionSplit,
    CertInvalid,
    NotRun,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TcpProbeStatus {
    Ok,
    ConnectFailed,
    Blocked16Kb,
    FreezeAfterThreshold,
    WhitelistSniOk,
    Error,
}

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
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EndpointProbeStatus {
    Ok,
    Failed,
    Blocked,
    NotRun,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramVerdict {
    Ok,
    Slow,
    Partial,
    Blocked,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramTransferStatus {
    Ok,
    Slow,
    Stalled,
    Blocked,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ThroughputProbeStatus {
    Measured,
    HttpUnreachable,
    InvalidTarget,
}

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
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DomainObservationFact {
    pub host: String,
    #[serde(default = "default_http_probe_status_not_run")]
    pub http_status: HttpProbeStatus,
    #[serde(default = "default_tls_probe_status_not_run")]
    pub tls13_status: TlsProbeStatus,
    #[serde(default = "default_tls_probe_status_not_run")]
    pub tls12_status: TlsProbeStatus,
    #[serde(default = "default_tls_probe_status_not_run")]
    pub tls_ech_status: TlsProbeStatus,
    #[serde(default)]
    pub tls_ech_version: Option<String>,
    #[serde(default)]
    pub tls_ech_error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_ech_resolution_detail: Option<String>,
    #[serde(default = "default_transport_failure_none")]
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TcpObservationFact {
    pub provider: String,
    pub status: TcpProbeStatus,
    #[serde(default)]
    pub selected_sni: Option<String>,
    #[serde(default)]
    pub bytes_sent: Option<usize>,
    #[serde(default)]
    pub responses_seen: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub freeze_threshold_bytes: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt_port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt_port_status: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QuicObservationFact {
    pub host: String,
    pub status: QuicProbeStatus,
    #[serde(default = "default_transport_failure_none")]
    pub transport_failure: TransportFailureKind,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ServiceObservationFact {
    pub service: String,
    #[serde(default = "default_http_probe_status_not_run")]
    pub bootstrap_status: HttpProbeStatus,
    #[serde(default = "default_http_probe_status_not_run")]
    pub media_status: HttpProbeStatus,
    #[serde(default = "default_endpoint_probe_status_not_run")]
    pub endpoint_status: EndpointProbeStatus,
    #[serde(default = "default_transport_failure_none")]
    pub endpoint_failure: TransportFailureKind,
    #[serde(default = "default_quic_probe_status_not_run")]
    pub quic_status: QuicProbeStatus,
    #[serde(default = "default_transport_failure_none")]
    pub quic_failure: TransportFailureKind,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CircumventionObservationFact {
    pub tool: String,
    #[serde(default = "default_http_probe_status_not_run")]
    pub bootstrap_status: HttpProbeStatus,
    #[serde(default = "default_endpoint_probe_status_not_run")]
    pub handshake_status: EndpointProbeStatus,
    #[serde(default = "default_transport_failure_none")]
    pub handshake_failure: TransportFailureKind,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TelegramObservationFact {
    pub verdict: TelegramVerdict,
    #[serde(default)]
    pub quality_score: i32,
    #[serde(default = "default_telegram_transfer_status_error")]
    pub download_status: TelegramTransferStatus,
    #[serde(default = "default_telegram_transfer_status_error")]
    pub upload_status: TelegramTransferStatus,
    #[serde(default)]
    pub dc_reachable: usize,
    #[serde(default)]
    pub dc_total: usize,
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyObservationFact {
    #[serde(default)]
    pub candidate_id: Option<String>,
    #[serde(default)]
    pub candidate_label: Option<String>,
    #[serde(default)]
    pub candidate_family: Option<String>,
    #[serde(default = "default_strategy_probe_protocol_candidate")]
    pub protocol: StrategyProbeProtocol,
    #[serde(default = "default_strategy_probe_status_failed")]
    pub status: StrategyProbeStatus,
    #[serde(default = "default_tls_probe_status_not_run")]
    pub tls_ech_status: TlsProbeStatus,
    #[serde(default)]
    pub tls_ech_version: Option<String>,
    #[serde(default)]
    pub tls_ech_error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_ech_resolution_detail: Option<String>,
    #[serde(default = "default_transport_failure_none")]
    pub transport_failure: TransportFailureKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_error: Option<String>,
    #[serde(default)]
    pub h3_advertised: bool,
}

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

// --- Serde default functions (used only by types in this module) ---

fn default_transport_failure_none() -> TransportFailureKind {
    TransportFailureKind::None
}

fn default_http_probe_status_not_run() -> HttpProbeStatus {
    HttpProbeStatus::NotRun
}

fn default_tls_probe_status_not_run() -> TlsProbeStatus {
    TlsProbeStatus::NotRun
}

fn default_quic_probe_status_not_run() -> QuicProbeStatus {
    QuicProbeStatus::NotRun
}

fn default_endpoint_probe_status_not_run() -> EndpointProbeStatus {
    EndpointProbeStatus::NotRun
}

fn default_telegram_transfer_status_error() -> TelegramTransferStatus {
    TelegramTransferStatus::Error
}

fn default_strategy_probe_protocol_candidate() -> StrategyProbeProtocol {
    StrategyProbeProtocol::Candidate
}

fn default_strategy_probe_status_failed() -> StrategyProbeStatus {
    StrategyProbeStatus::Failed
}
