use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;
use rustls::client::danger::ServerCertVerifier;

use super::key_log::TlsKeyLogCallback;
use crate::transport::{ConnectionStream, RouteExperimentReport, TransportFailureStage};

#[derive(Clone, Debug)]
pub struct TlsObservation {
    pub status: String,
    pub version: Option<String>,
    pub error: Option<String>,
    pub failure_stage: Option<ProbeStreamFailureStage>,
    pub failure_duration_ms: Option<u64>,
    pub certificate_anomaly: bool,
    pub ech_resolution_detail: Option<String>,
    pub ech_bootstrap_policy: Option<String>,
    pub ech_bootstrap_resolver_id: Option<String>,
    pub ech_outer_extension_policy: Option<String>,
    pub ech_first_flight_plan: Option<String>,
    pub tcp_connect_ms: Option<u64>,
    pub tls_handshake_ms: Option<u64>,
    pub cert_chain_length: Option<usize>,
    pub cert_issuer: Option<String>,
    pub local_socket_ttl: Option<u8>,
    pub ja3_fingerprint: Option<String>,
    /// Numeric TLS alert code when the handshake fails with AlertReceived.
    pub tls_alert_code: Option<u8>,
    /// Human-readable alert description (e.g. "HandshakeFailure").
    pub tls_alert_description: Option<String>,
    /// Whether a ServerHello was received before the error occurred.
    pub tls_server_hello_received: Option<bool>,
    /// DPI firmware signature inferred from the alert code and timing.
    pub tls_dpi_signature: Option<String>,
    pub connected_addr: Option<std::net::SocketAddr>,
    pub local_addr: Option<std::net::SocketAddr>,
    pub cdn_provider: Option<String>,
    pub route_report: Option<RouteExperimentReport>,
}

#[derive(Clone, Copy, Debug)]
pub enum TlsClientProfile {
    Auto,
    Tls12Only,
    Tls13Only,
    Tls13WithEch,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ApplicationProtocolPolicy {
    TemplateDefault,
    Http11Only,
}

pub struct ProbeStreamOptions<'a> {
    pub verify_certificates: bool,
    pub profile: TlsClientProfile,
    pub application_protocol: ApplicationProtocolPolicy,
    pub tls_verifier: Option<&'a std::sync::Arc<dyn ServerCertVerifier>>,
    pub key_log: Option<&'a TlsKeyLogCallback>,
}

impl Default for ProbeStreamOptions<'_> {
    fn default() -> Self {
        Self {
            verify_certificates: true,
            profile: TlsClientProfile::Auto,
            application_protocol: ApplicationProtocolPolicy::TemplateDefault,
            tls_verifier: None,
            key_log: None,
        }
    }
}

pub struct ProbeStreamResult {
    pub stream: ConnectionStream,
    pub negotiated_alpn: Option<String>,
    pub tls_template_first_flight_plan: Option<TlsTemplateFirstFlightPlan>,
    pub tcp_connect_ms: u64,
    pub tls_handshake_ms: u64,
    pub cert_chain_length: Option<usize>,
    pub cert_issuer: Option<String>,
    pub local_socket_ttl: Option<u8>,
    pub ja3_fingerprint: Option<String>,
    pub connected_addr: Option<std::net::SocketAddr>,
    pub local_addr: Option<std::net::SocketAddr>,
    pub cdn_provider: Option<String>,
    pub route_report: Option<RouteExperimentReport>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ProbeStreamFailureStage {
    DnsResolution,
    TcpConnect,
    Socks5Negotiation,
    StreamSetup,
    TlsHandshake,
}

impl ProbeStreamFailureStage {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::DnsResolution => "dns_resolution",
            Self::TcpConnect => "tcp_connect",
            Self::Socks5Negotiation => "socks5_negotiation",
            Self::StreamSetup => "stream_setup",
            Self::TlsHandshake => "tls_handshake",
        }
    }
}

impl From<TransportFailureStage> for ProbeStreamFailureStage {
    fn from(stage: TransportFailureStage) -> Self {
        match stage {
            TransportFailureStage::DnsResolution => Self::DnsResolution,
            TransportFailureStage::TcpConnect => Self::TcpConnect,
            TransportFailureStage::Socks5Negotiation => Self::Socks5Negotiation,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProbeStreamError {
    pub stage: ProbeStreamFailureStage,
    pub message: String,
    pub duration_ms: u64,
    pub tcp_connect_ms: Option<u64>,
    pub connected_addr: Option<std::net::SocketAddr>,
}

impl std::fmt::Display for ProbeStreamError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for ProbeStreamError {}
