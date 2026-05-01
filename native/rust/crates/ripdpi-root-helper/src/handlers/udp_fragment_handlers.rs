use std::os::fd::{IntoRawFd, RawFd};

use ripdpi_privileged_ops as platform;
use ripdpi_root_helper_protocol::{HelperResponse, IpFragUdpParams};
use tracing::{debug, error};

use super::fd_adoption::adopt_udp_socket;

pub fn handle_send_ip_fragmented_udp(fd: RawFd, params: IpFragUdpParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, split = params.split_offset, "send_ip_fragmented_udp");

    let target: std::net::SocketAddr = match params.target_addr.parse() {
        Ok(addr) => addr,
        Err(e) => return (HelperResponse::error(format!("invalid target_addr: {e}")), None),
    };

    let socket = adopt_udp_socket(fd);
    match platform::send_ip_fragmented_udp(
        &socket,
        target,
        &params.payload,
        params.split_offset,
        params.default_ttl,
        None,
        params.disorder,
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        params.ipv4_identification,
    ) {
        Ok(()) => {
            // Return fd to caller.
            let _ = socket.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), None)
        }
        Err(e) => {
            let _ = socket.into_raw_fd();
            error!(%e, "send_ip_fragmented_udp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}
