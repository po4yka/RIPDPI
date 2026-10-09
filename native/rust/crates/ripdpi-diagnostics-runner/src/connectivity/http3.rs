use ripdpi_diagnostics_contracts::{Http3ProbeConfig, ProbeDetail, ProbeResult};
use ripdpi_diagnostics_transport::transport::TransportConfig;
use rustls::client::danger::ServerCertVerifier;
use std::sync::{Arc, atomic::AtomicBool};
/// Collect one verified HTTP/3 request without fallback to another protocol.
pub fn run_http3_probe(
    config: &Http3ProbeConfig,
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeResult {
    let facts = ripdpi_diagnostics_http::http3::probe_http3(config, transport, raw_path, cancel, verifier);
    let outcome = match facts.status.as_str() {
        "COMPLETE" => "http3_complete",
        "HTTP_ERROR" => "http3_http_error",
        "CANCELLED" => "http3_cancelled",
        _ if facts.http3_validated => "http3_incomplete",
        "FAILED" | "TIMEOUT" if facts.attempt_count > 0 => "http3_unavailable",
        _ => "http3_inconclusive",
    };
    ProbeResult {
        probe_type: "http3".into(),
        target: config.host.clone(),
        outcome: outcome.into(),
        details: vec![ProbeDetail { key: "http3Evidence".into(), value: facts.to_detail_json().unwrap_or_default() }],
    }
}
