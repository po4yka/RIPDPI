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
    pub transport_open_ms: Option<u64>,
    pub tls_handshake_ms: Option<u64>,
    pub cert_chain_length: Option<usize>,
    pub cert_issuer: Option<String>,
    pub local_socket_ttl: Option<u8>,
    pub ja3_fingerprint: Option<String>,
    pub client_hello_capture: ClientHelloCapture,
    /// Numeric TLS alert code when the handshake fails with AlertReceived.
    pub tls_alert_code: Option<u8>,
    /// Human-readable alert description (e.g. "HandshakeFailure").
    pub tls_alert_description: Option<String>,
    /// Whether rustls processed a ServerHello. `None` when TLS evidence is unavailable.
    pub tls_server_hello_received: Option<bool>,
    pub connected_addr: Option<std::net::SocketAddr>,
    pub local_addr: Option<std::net::SocketAddr>,
    pub cdn_provider: Option<String>,
    pub route_report: Option<RouteExperimentReport>,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum ClientHelloCapture {
    #[default]
    NotSent,
    IncompleteOrInvalid,
    Complete,
}

impl ClientHelloCapture {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::NotSent => "not_sent",
            Self::IncompleteOrInvalid => "incomplete_or_invalid",
            Self::Complete => "complete",
        }
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(super) struct ClientHelloEvidence {
    pub(super) ja3_fingerprint: Option<String>,
    pub(super) capture: ClientHelloCapture,
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
    pub profile: TlsClientProfile,
    pub application_protocol: ApplicationProtocolPolicy,
    /// Replaces the default CA, server-name, and SPKI verification policy when set.
    pub tls_verifier: Option<&'a std::sync::Arc<dyn ServerCertVerifier>>,
    pub key_log: Option<&'a TlsKeyLogCallback>,
}

impl Default for ProbeStreamOptions<'_> {
    fn default() -> Self {
        Self {
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
    pub transport_open_ms: u64,
    pub tls_handshake_ms: u64,
    pub cert_chain_length: Option<usize>,
    pub cert_issuer: Option<String>,
    pub local_socket_ttl: Option<u8>,
    pub ja3_fingerprint: Option<String>,
    pub client_hello_capture: ClientHelloCapture,
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
    pub transport_open_ms: Option<u64>,
    pub connected_addr: Option<std::net::SocketAddr>,
    /// For a TLS handshake failure, whether the ServerHello was processed first.
    /// `None` when TLS evidence is unavailable.
    pub tls_server_hello_received: Option<bool>,
    pub(crate) tls_alert: Option<rustls::AlertDescription>,
    pub(crate) certificate_anomaly: bool,
    pub(crate) tls_handshake_ms: Option<u64>,
    pub(super) client_hello: Option<Box<ClientHelloEvidence>>,
}

impl ProbeStreamError {
    pub fn rejected_server_certificate(&self) -> bool {
        self.certificate_anomaly
    }
}

impl std::fmt::Display for ProbeStreamError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for ProbeStreamError {}
