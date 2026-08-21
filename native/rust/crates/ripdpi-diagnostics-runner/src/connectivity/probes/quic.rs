use ripdpi_packets::{QUIC_V1_VERSION, build_realistic_quic_initial, parse_quic_initial};

use crate::connectivity::adapters::transport::{TransportConfig, quic_connect_target, relay_udp_payload_observed};
use crate::connectivity::adapters::util::now_ms;
use crate::types::{ProbeDetail, ProbeResult, QuicTarget};

use super::support::append_route_details;

pub fn run_quic_probe(target: &QuicTarget, transport: &TransportConfig) -> ProbeResult {
    let started = now_ms();
    let connect_target = quic_connect_target(target);
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str())).unwrap_or_default();
    let response = relay_udp_payload_observed(std::slice::from_ref(&connect_target), target.port, transport, &payload);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error, connected_addr, local_addr, route_report) = match response {
        Ok(result) if parse_quic_initial(&result.payload).is_some() => (
            "quic_initial_response".to_string(),
            "quic_initial_response".to_string(),
            "none".to_string(),
            result.connected_addr,
            result.local_addr,
            result.route_report,
        ),
        Ok(result) if !result.payload.is_empty() => (
            "quic_response".to_string(),
            "quic_response".to_string(),
            "none".to_string(),
            result.connected_addr,
            result.local_addr,
            result.route_report,
        ),
        Ok(result) => (
            "quic_empty".to_string(),
            "quic_empty".to_string(),
            "none".to_string(),
            result.connected_addr,
            result.local_addr,
            result.route_report,
        ),
        Err(err) => ("quic_error".to_string(), "quic_error".to_string(), err.to_string(), None, None, None),
    };
    let mut result = ProbeResult {
        probe_type: "quic_reachability".to_string(),
        target: target.host.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "status".to_string(), value: status },
            ProbeDetail { key: "error".to_string(), value: error },
            ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
            ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
        ],
    };
    if let Some(addr) = connected_addr {
        result.details.push(ProbeDetail { key: "connectedIp".to_string(), value: addr.ip().to_string() });
    }
    append_route_details(&mut result.details, "", local_addr, route_report.as_ref());
    result
}
