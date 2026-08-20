use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::tls::{
    TlsClientProfile, TlsKeyLogCallback, try_tls_handshake, try_tls_handshake_with_key_log,
};
use crate::connectivity::adapters::transport::{TransportConfig, connect_transport_observed};

use super::target_parse::connect_target_from_parts;
use super::types::EndpointProbeObservation;

pub(super) fn run_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    tls_name: Option<&str>,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> EndpointProbeObservation {
    let Some(target) = connect_target_from_parts(host, connect_ip) else {
        return EndpointProbeObservation {
            status: "not_run".to_string(),
            error: "not_run".to_string(),
            local_addr: None,
            route_report: None,
        };
    };
    if tls_name.is_some() || port == 443 {
        let server_name = tls_name.or(host).unwrap_or_default();
        let observation = match key_log {
            Some(key_log) => try_tls_handshake_with_key_log(
                &target,
                port,
                transport,
                server_name,
                true,
                TlsClientProfile::Auto,
                tls_verifier,
                Some(key_log),
            ),
            None => {
                try_tls_handshake(&target, port, transport, server_name, true, TlsClientProfile::Auto, tls_verifier)
            }
        };
        EndpointProbeObservation {
            status: observation.status,
            error: observation.error.unwrap_or_else(|| "none".to_string()),
            local_addr: observation.local_addr,
            route_report: observation.route_report,
        }
    } else {
        match connect_transport_observed(std::slice::from_ref(&target), port, transport) {
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
            Err(err) => EndpointProbeObservation {
                status: "tcp_connect_failed".to_string(),
                error: err.to_string(),
                local_addr: None,
                route_report: None,
            },
        }
    }
}
