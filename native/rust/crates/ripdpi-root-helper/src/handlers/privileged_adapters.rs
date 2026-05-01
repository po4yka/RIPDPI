use std::os::fd::RawFd;

use ripdpi_privileged_ops as platform;
use ripdpi_privileged_ops::{IcmpWrappedUdpRecvFilter, IcmpWrappedUdpSpec, SynHideTcpSpec};
use ripdpi_root_helper_protocol::HelperResponse;
use tracing::{debug, error};

pub fn handle_send_syn_hide_tcp(params: SynHideTcpSpec) -> (HelperResponse, Option<RawFd>) {
    debug!(source = %params.source, target = %params.target, marker = ?params.marker_kind, "send_syn_hide_tcp");
    match platform::send_syn_hide_tcp(params, None) {
        Ok(()) => (HelperResponse::success(serde_json::Value::Null), None),
        Err(error) => {
            error!(%error, "send_syn_hide_tcp failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}

pub fn handle_send_icmp_wrapped_udp(params: IcmpWrappedUdpSpec) -> (HelperResponse, Option<RawFd>) {
    debug!(
        peer = %params.peer,
        service_port = params.service_port,
        role = ?params.role,
        len = params.payload.len(),
        "send_icmp_wrapped_udp"
    );
    match platform::send_icmp_wrapped_udp(&params, None) {
        Ok(()) => (HelperResponse::success(serde_json::Value::Null), None),
        Err(error) => {
            error!(%error, "send_icmp_wrapped_udp failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}

pub fn handle_recv_icmp_wrapped_udp(params: IcmpWrappedUdpRecvFilter) -> (HelperResponse, Option<RawFd>) {
    debug!(
        bind_ip = %params.bind_ip,
        session_id = ?params.session_id,
        expected_code = ?params.expected_code,
        expected_role = ?params.expected_role,
        timeout_ms = params.timeout_ms,
        "recv_icmp_wrapped_udp"
    );
    match platform::recv_icmp_wrapped_udp(params, None) {
        Ok(message) => match serde_json::to_value(message) {
            Ok(data) => (HelperResponse::success(data), None),
            Err(error) => {
                (HelperResponse::error(format!("failed to serialize recv_icmp_wrapped_udp response: {error}")), None)
            }
        },
        Err(error) => {
            error!(%error, "recv_icmp_wrapped_udp failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}
