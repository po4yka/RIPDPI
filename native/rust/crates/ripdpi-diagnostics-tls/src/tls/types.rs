use ripdpi_tls_profiles::TlsTemplateFirstFlightPlan;

use crate::transport::{ConnectionStream, RouteExperimentReport};

#[derive(Clone, Debug)]
pub struct TlsObservation {
    pub status: String,
    pub version: Option<String>,
    pub error: Option<String>,
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
    AutoHttp11,
    Tls12Only,
    Tls13Only,
    Tls13WithEch,
}
pub struct ProbeStreamResult {
    pub stream: ConnectionStream,
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
