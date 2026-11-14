use std::os::fd::RawFd;

use ripdpi_privileged_ops as platform;
use ripdpi_root_helper_protocol::{HelperResponse, IpFragUdpParams};
use tracing::{debug, error};

use super::fd_adoption::adopt_udp_socket;
use super::fragment_error::fragment_error_response;

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
            (fragment_error_response(e), None)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::UdpSocket;
    use std::os::fd::IntoRawFd;

    #[test]
    fn invalid_udp_split_reports_pre_send_failure_and_closes_descriptor() {
        let socket = UdpSocket::bind("127.0.0.1:0").expect("UDP socket");
        let source = socket.local_addr().expect("source address");
        let params = IpFragUdpParams {
            target_addr: "127.0.0.1:443".to_owned(),
            payload: vec![0; 1200],
            split_offset: 1208,
            default_ttl: 64,
            disorder: false,
            ipv4_identification: None,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt_fragmentable: false,
            ipv6_routing: false,
            ipv6_second_frag_next_override: None,
        };
        let (response, reply_fd) = handle_send_ip_fragmented_udp(socket.into_raw_fd(), params);
        assert!(!response.ok);
        assert!(reply_fd.is_none());
        let expected_kind =
            if cfg!(any(target_os = "linux", target_os = "android")) { "InvalidInput" } else { "Unsupported" };
        assert_eq!(response.data["error_kind"], expected_kind);
        assert_eq!(response.data["payload_commit_uncertain"], false);
        UdpSocket::bind(source).expect("handler released its inbound descriptor");
    }
}
