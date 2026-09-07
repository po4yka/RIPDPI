use ripdpi_packets::{QUIC_V1_VERSION, QuicResponseKind, build_probe_quic_initial, validate_quic_response};

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
    let payload = build_probe_quic_initial(QUIC_V1_VERSION, Some(host_name));
    let response =
        payload.as_deref().ok_or_else(|| std::io::Error::other("QUIC Initial generation failed").into()).and_then(
            |payload| relay_udp_payload_observed(std::slice::from_ref(&connect_target), port, transport, payload),
        );
    let kind = response
        .as_ref()
        .ok()
        .and_then(|result| payload.as_deref().and_then(|request| validate_quic_response(request, &result.payload)));
    match response {
        Ok(result) if kind == Some(QuicResponseKind::Initial) => EndpointProbeObservation {
            status: "quic_initial_response".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Ok(result) if kind.is_some() => EndpointProbeObservation {
            status: "quic_response".to_string(),
            error: "none".to_string(),
            local_addr: result.local_addr,
            route_report: result.route_report,
        },
        Ok(result) => EndpointProbeObservation {
            status: if result.payload.is_empty() { "quic_empty" } else { "quic_error" }.to_string(),
            error: if result.payload.is_empty() { "none" } else { "invalid QUIC response" }.to_string(),
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
