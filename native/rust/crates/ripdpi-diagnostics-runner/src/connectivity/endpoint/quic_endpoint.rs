use std::net::IpAddr;
use std::sync::atomic::AtomicBool;

use ripdpi_packets::{canonical_quic_probe_host, validate_quic_probe_response};

use crate::connectivity::adapters::transport::{TargetAddress, TransportConfig, relay_quic_probe_observed};
use crate::connectivity::probes::{build_probe_flight, quic_reply_status};

use super::endpoint_stop_reason;
use super::target_parse::connect_target_from_parts;
use super::types::EndpointProbeObservation;

pub(in crate::connectivity) fn run_quic_endpoint_probe(
    host: Option<&str>,
    connect_ip: Option<&str>,
    port: u16,
    transport: &TransportConfig,
    cancel: &AtomicBool,
) -> EndpointProbeObservation {
    if host.is_none() && connect_ip.is_none() {
        return EndpointProbeObservation::without_connection("not_run", "not_run");
    }
    if let Some(reason) = endpoint_stop_reason(cancel) {
        return EndpointProbeObservation::without_connection("interrupted", reason);
    }
    let Some(host) = host.filter(|host| canonical_quic_probe_host(host).is_some()) else {
        return EndpointProbeObservation::without_connection("invalid_target", "invalid QUIC host");
    };
    if port == 0 || connect_ip.is_some_and(|ip| ip.parse::<IpAddr>().is_err()) {
        return EndpointProbeObservation::without_connection("invalid_target", "invalid QUIC endpoint");
    }
    let target = connect_target_from_parts(Some(host), connect_ip).unwrap_or_else(|| TargetAddress::Host(host.into()));
    match relay_quic_probe_observed(
        &[target],
        port,
        transport,
        cancel,
        &mut || build_probe_flight(host),
        &|request, response| validate_quic_probe_response(request, response).is_some(),
    ) {
        Ok((result, request)) => {
            let Some(kind) = validate_quic_probe_response(&request, &result.payload) else {
                return EndpointProbeObservation::without_connection("quic_error", "invalid QUIC response");
            };
            EndpointProbeObservation {
                status: quic_reply_status(kind).into(),
                error: "none".into(),
                local_addr: result.local_addr,
                route_report: result.route_report,
            }
        }
        Err(error) => match endpoint_stop_reason(cancel) {
            Some(reason) => EndpointProbeObservation::without_connection("interrupted", reason),
            None => EndpointProbeObservation::without_connection("quic_error", &error.to_string()),
        },
    }
}
