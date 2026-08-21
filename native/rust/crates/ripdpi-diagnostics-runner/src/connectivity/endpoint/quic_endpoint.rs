use ripdpi_packets::{QUIC_V1_VERSION, build_realistic_quic_initial, parse_quic_initial};

use crate::connectivity::adapters::transport::{TargetAddress, TransportConfig, relay_udp_payload_observed};

use super::target_parse::connect_target_from_parts;
use super::types::EndpointProbeObservation;

pub(super) fn run_quic_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    transport: &TransportConfig,
) -> EndpointProbeObservation {
    let Some(host_name) = host else {
        return EndpointProbeObservation {
            status: "not_run".to_string(),
            error: "not_run".to_string(),
            local_addr: None,
            route_report: None,
        };
    };
    let connect_target = connect_target_from_parts(Some(host_name), connect_ip)
        .unwrap_or_else(|| TargetAddress::Host(host_name.to_string()));
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(host_name)).unwrap_or_default();
    match relay_udp_payload_observed(std::slice::from_ref(&connect_target), port, transport, &payload) {
        Ok(result) if parse_quic_initial(&result.payload).is_some() => EndpointProbeObservation {
            status: "quic_initial_response".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Ok(result) if !result.payload.is_empty() => EndpointProbeObservation {
            status: "quic_response".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Ok(result) => EndpointProbeObservation {
            status: "quic_empty".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Err(err) => EndpointProbeObservation {
            status: "quic_error".to_string(),
            error: err.to_string(),
            local_addr: None,
            route_report: None,
        },
    }
}
