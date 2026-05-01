mod quic_endpoint;
mod target_parse;
mod tcp_tls_endpoint;
mod throughput;
mod types;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::http::HttpObservation;
use crate::transport::TransportConfig;
use crate::types::ThroughputTarget;

pub(super) use types::{EndpointProbeObservation, ThroughputSample};

pub(super) fn measure_throughput_window(target: &ThroughputTarget, transport: &TransportConfig) -> ThroughputSample {
    throughput::measure_throughput_window(target, transport)
}

pub(super) fn probe_http_url(
    url: &str,
    connect_ip: Option<&str>,
    connect_ips: &[String],
    port_override: Option<u16>,
    transport: &TransportConfig,
) -> HttpObservation {
    throughput::probe_http_url(url, connect_ip, connect_ips, port_override, transport)
}

pub(super) fn run_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    tls_name: Option<&str>,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> EndpointProbeObservation {
    tcp_tls_endpoint::run_endpoint_probe(host, connect_ip, port, tls_name, transport, tls_verifier)
}

pub(super) fn run_quic_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    transport: &TransportConfig,
) -> EndpointProbeObservation {
    quic_endpoint::run_quic_endpoint_probe(host, connect_ip, port, transport)
}

pub(super) fn is_probe_failure(status: &str) -> bool {
    if status.starts_with("http_status_3") {
        return false; // 3xx redirects are not probe failures
    }
    !matches!(
        status,
        "not_run"
            | "http_ok"
            | "http_redirect"
            | "tls_ok"
            | "tcp_connect_ok"
            | "quic_initial_response"
            | "quic_response"
    )
}

/// Returns true for HTTP status codes that indicate the server is reachable
/// but returned a non-success response (rate limits, server errors, etc.).
/// These should NOT be classified as censorship blocking since the server responded.
pub(super) fn is_server_error(status: &str) -> bool {
    status.starts_with("http_status_4") || status.starts_with("http_status_5")
}

#[cfg(test)]
mod tests;
