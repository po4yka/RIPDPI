use std::os::fd::RawFd;

use ripdpi_privileged_ops as platform;
use ripdpi_root_helper_protocol::{HelperResponse, IpFragUdpParams};
use tracing::{debug, error};

use super::fd_adoption::adopt_udp_socket;

pub fn handle_send_ip_fragmented_udp(fd: RawFd, params: IpFragUdpParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, split = params.split_offset, "send_ip_fragmented_udp");

    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper dispatch
    // loop, which guarantees a live UDP socket exclusively owned by this
    // handler. Adopting the fd before parsing `target_addr` makes the
    // parse-error path close it exactly once on `socket` drop. This command
    // never returns a reply fd, so the adopted `socket` drops on every exit
    // path below, closing the kernel descriptor exactly once — never leaked
    // or double-closed.
    let socket = unsafe { adopt_udp_socket(fd) };

    let target: std::net::SocketAddr = match params.target_addr.parse() {
        Ok(addr) => addr,
        Err(e) => return (HelperResponse::error(format!("invalid target_addr: {e}")), None),
    };

    match platform::send_ip_fragmented_udp(
        &socket,
        target,
        &params.payload,
        params.split_offset,
        params.default_ttl,
        None,
        params.disorder,
        ripdpi_ipfrag::Ipv6ExtHeaders {
            hop_by_hop: params.ipv6_hop_by_hop,
            dest_opt: params.ipv6_dest_opt,
            dest_opt_fragmentable: params.ipv6_dest_opt_fragmentable,
            routing: params.ipv6_routing,
            second_frag_next_override: params.ipv6_second_frag_next_override,
        },
        params.ipv4_identification,
    ) {
        Ok(()) => {
            // Drop `socket` to close the descriptor exactly once.
            (HelperResponse::success(serde_json::Value::Null), None)
        }
        Err(e) => {
            // Drop `socket` to close the descriptor exactly once on error.
            error!(%e, "send_ip_fragmented_udp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}
