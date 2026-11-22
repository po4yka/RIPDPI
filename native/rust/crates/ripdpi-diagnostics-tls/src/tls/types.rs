use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;
use rustls::client::EchStatus;
use rustls::client::danger::ServerCertVerifier;

use super::key_log::TlsKeyLogCallback;
use crate::transport::{ConnectionStream, RouteExperimentReport, TransportFailureStage};

#[derive(Clone, Debug)]
pub struct TlsObservation {
    pub status: String,
    pub version: Option<String>,
    pub negotiated_alpn: Option<String>,
    pub error: Option<String>,
    pub failure_stage: Option<ProbeStreamFailureStage>,
    pub failure_duration_ms: Option<u64>,
    /// A local verifier reported a rejection. Use certificate_verification for the trust verdict.
    pub certificate_anomaly: bool,
    pub ech_resolution_detail: Option<String>,
    pub ech_config_source: Option<EchConfigSource>,
    pub ech_status: Option<EchStatus>,
    pub ech_initial_attempt: Option<EchInitialAttempt>,
    pub ech_retry_skipped_reason: Option<&'static str>,
    pub ech_bootstrap_policy: Option<String>,
    pub ech_bootstrap_resolver_id: Option<String>,
    pub ech_outer_extension_policy: Option<String>,
    pub ech_first_flight_plan: Option<String>,
    pub transport_open_ms: Option<u64>,
    pub tls_handshake_ms: Option<u64>,
    pub cert_chain_length: Option<usize>,
    pub certificate_verification: CertificateVerification,
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
    /// Whether a handshake response or alert followed the ClientHello.
    pub tls_peer_response_after_client_hello: Option<bool>,
    pub connected_addr: Option<std::net::SocketAddr>,
    pub local_addr: Option<std::net::SocketAddr>,
    pub cdn_provider: Option<String>,
    pub route_report: Option<RouteExperimentReport>,
}

/// What the TLS probe can assert about certificate verification.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CertificateVerification {
    NotCompleted,
    VerifiedWebPki,
    RejectedWebPki,
    Skipped,
    /// A caller-supplied policy owns the decision. No WebPKI verdict is inferred.
    Custom,
}

impl CertificateVerification {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::NotCompleted => "not_completed",
            Self::VerifiedWebPki => "verified_webpki",
            Self::RejectedWebPki => "rejected_webpki",
            Self::Custom => "custom",
            Self::Skipped => "skipped",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EchConfigSource {
    DnsHttps,
    CdnFallback(&'static str),
    RetryConfigs,
}

impl EchConfigSource {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::DnsHttps => "dns_https",
            Self::CdnFallback(_) => "cdn_fallback",
            Self::RetryConfigs => "retry_configs",
        }
    }

    pub const fn cdn_provider(self) -> Option<&'static str> {
        match self {
            Self::CdnFallback(provider) => Some(provider),
            _ => None,
        }
    }
}

/// The rejected ECH attempt before starting one retry to the same peer.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EchInitialAttempt {
    pub connected_addr: std::net::SocketAddr,
    pub transport_open_ms: u64,
    pub tls_handshake_ms: Option<u64>,
    pub ja3_fingerprint: Option<String>,
    pub client_hello_capture: ClientHelloCapture,
    pub ech_config_source: EchConfigSource,
    pub certificate_verification: CertificateVerification,
    pub error: String,
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
    ComparisonTls13,
    ComparisonEchGrease,
    ComparisonEch,
}

impl TlsClientProfile {
    pub(super) const fn is_comparison(self) -> bool {
        matches!(self, Self::ComparisonTls13 | Self::ComparisonEchGrease | Self::ComparisonEch)
    }

    pub(super) const fn uses_real_ech(self) -> bool {
        matches!(self, Self::Tls13WithEch | Self::ComparisonEch)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ApplicationProtocolPolicy {
    TemplateDefault,
    Http11Only,
}

/// Overrides are explicit so an unchecked handshake cannot be reported as authenticated.
#[derive(Clone, Copy)]
pub enum ProbeCertificatePolicy<'a> {
    WebPki,
    Custom(&'a std::sync::Arc<dyn ServerCertVerifier>),
    Skip,
}

impl<'a> From<Option<&'a std::sync::Arc<dyn ServerCertVerifier>>> for ProbeCertificatePolicy<'a> {
    fn from(verifier: Option<&'a std::sync::Arc<dyn ServerCertVerifier>>) -> Self {
        verifier.map_or(Self::WebPki, Self::Custom)
    }
}

impl ProbeCertificatePolicy<'_> {
    pub(super) fn override_verifier(&self) -> Option<&std::sync::Arc<dyn ServerCertVerifier>> {
        use std::sync::{Arc, LazyLock};

        static SKIP: LazyLock<Arc<dyn ServerCertVerifier>> =
            LazyLock::new(|| Arc::new(super::verifier::NoCertificateVerification));
        match self {
            Self::WebPki => None,
            Self::Custom(verifier) => Some(verifier),
            Self::Skip => Some(&SKIP),
        }
    }

    pub(super) const fn verification_after_handshake(self) -> CertificateVerification {
        match self {
            Self::WebPki => CertificateVerification::VerifiedWebPki,
            Self::Custom(_) => CertificateVerification::Custom,
            Self::Skip => CertificateVerification::Skipped,
        }
    }
}

pub struct ProbeStreamOptions<'a> {
    pub profile: TlsClientProfile,
    pub application_protocol: ApplicationProtocolPolicy,
    /// Selects the CA, server-name, and SPKI verification policy.
    pub certificate_policy: ProbeCertificatePolicy<'a>,
    pub key_log: Option<&'a TlsKeyLogCallback>,
}

impl Default for ProbeStreamOptions<'_> {
    fn default() -> Self {
        Self {
            profile: TlsClientProfile::Auto,
            application_protocol: ApplicationProtocolPolicy::TemplateDefault,
            certificate_policy: ProbeCertificatePolicy::WebPki,
            key_log: None,
        }
    }
}

pub struct ProbeStreamResult {
    pub stream: ConnectionStream,
    pub negotiated_alpn: Option<String>,
    pub tls_template_first_flight_plan: Option<TlsTemplateFirstFlightPlan>,
    pub(super) ech_config_source: Option<EchConfigSource>,
    pub(super) ech_initial_attempt: Option<Box<EchInitialAttempt>>,
    pub transport_open_ms: u64,
    pub tls_handshake_ms: u64,
    pub cert_chain_length: Option<usize>,
    pub certificate_verification: CertificateVerification,
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
    pub certificate_verification: CertificateVerification,
    pub stage: ProbeStreamFailureStage,
    pub message: String,
    pub duration_ms: u64,
    pub transport_open_ms: Option<u64>,
    pub connected_addr: Option<std::net::SocketAddr>,
    /// For a TLS handshake failure, whether the ServerHello was processed first.
    /// `None` when TLS evidence is unavailable.
    pub tls_server_hello_received: Option<bool>,
    pub(super) peer_response_after_client_hello: Option<bool>,
    pub(crate) tls_alert: Option<rustls::AlertDescription>,
    pub(crate) certificate_anomaly: bool,
    pub(crate) tls_handshake_ms: Option<u64>,
    pub(super) client_hello: Option<Box<ClientHelloEvidence>>,
    pub(super) ech: Option<Box<EchErrorEvidence>>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct EchErrorEvidence {
    pub(super) config_source: Option<EchConfigSource>,
    pub(super) status: Option<EchStatus>,
    pub(super) rejected: bool,
    pub(super) lookup_failure: Option<super::config::EchLookupFailure>,
    pub(super) initial_attempt: Option<Box<EchInitialAttempt>>,
    pub(super) retry_skipped_reason: Option<&'static str>,
}

impl ProbeStreamError {
    pub fn rejected_server_certificate(&self) -> bool {
        self.certificate_anomaly
    }

    /// Report the ECH-specific setup or authenticated rejection observed in the comparison.
    pub fn comparison_ech_failure_outcome(&self) -> Option<&'static str> {
        use super::config::EchLookupFailure;

        let evidence = self.ech.as_ref()?;
        match evidence.lookup_failure.as_ref() {
            Some(EchLookupFailure::NotPublished) => Some("ech_config_missing"),
            Some(EchLookupFailure::ResolutionFailed(_)) => Some("ech_dns_failed"),
            Some(EchLookupFailure::InvalidConfig(_)) => Some("ech_config_invalid"),
            None if evidence.rejected => Some("ech_rejected"),
            None => None,
        }
    }
}

impl std::fmt::Display for ProbeStreamError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for ProbeStreamError {}
