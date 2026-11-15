use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use ripdpi_diagnostics_transport::transport::connect_transport_observed_with_abort;
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::tls::{
    ProbeStreamOptions, TlsKeyLogCallback, try_tls_handshake_targets_with_options_and_abort,
};
use crate::connectivity::adapters::transport::TransportConfig;

use super::endpoint_stop_reason;
use super::target_parse::connect_target_from_parts;
use super::types::EndpointProbeObservation;

pub(in crate::connectivity) fn run_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    tls_name: Option<&str>,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    cancel: &AtomicBool,
) -> EndpointProbeObservation {
    if host.is_none() && connect_ip.is_none() && tls_name.is_none() {
        return EndpointProbeObservation::without_connection("not_run", "not_run");
    }
    let should_abort = || endpoint_stop_reason(cancel);
    if let Some(reason) = should_abort() {
        return EndpointProbeObservation::without_connection("interrupted", reason);
    }
    if port == 0 || connect_ip.is_some_and(|ip| ip.parse::<IpAddr>().is_err()) {
        return EndpointProbeObservation::without_connection("invalid_target", "invalid TCP endpoint");
    }
    let Some(target) = connect_target_from_parts(host, connect_ip) else {
        return EndpointProbeObservation::without_connection("invalid_target", "missing TCP endpoint");
    };
    if tls_name.is_some() || port == 443 {
        let server_name = tls_name.or(host).or(connect_ip).unwrap_or_default();
        let options = ProbeStreamOptions { certificate_policy: tls_verifier.into(), key_log, ..Default::default() };
        let observation = try_tls_handshake_targets_with_options_and_abort(
            std::slice::from_ref(&target),
            port,
            transport,
            server_name,
            &options,
            &should_abort,
        );
        if observation.status != "tls_ok"
            && let Some(reason) = should_abort()
        {
            return EndpointProbeObservation::without_connection("interrupted", reason);
        }
        EndpointProbeObservation {
            status: observation.status,
            error: observation.error.unwrap_or_else(|| "none".to_string()),
            local_addr: observation.local_addr,
            route_report: observation.route_report,
        }
    } else {
        match connect_transport_observed_with_abort(std::slice::from_ref(&target), port, transport, &should_abort) {
            Ok(result) => {
                let stream = result.stream;
                let _ = stream.shutdown(std::net::Shutdown::Both);
                EndpointProbeObservation {
                    status: "tcp_connect_ok".to_string(),
                    error: "none".to_string(),
                    local_addr: result.local_addr,
                    route_report: result.route_report,
                }
            }
            Err(error) => match should_abort() {
                Some(reason) => EndpointProbeObservation::without_connection("interrupted", reason),
                None => EndpointProbeObservation::without_connection("tcp_connect_failed", &error.to_string()),
            },
        }
    }
}
