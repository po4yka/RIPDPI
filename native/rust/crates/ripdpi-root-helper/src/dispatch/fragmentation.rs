use std::os::fd::{FromRawFd, IntoRawFd, OwnedFd, RawFd};

use ripdpi_root_helper_protocol::{HelperRequest, IpFragTcpParams, IpFragUdpParams};

use crate::dispatch::{DispatchOutcome, decode_params, require_fd};
use crate::handlers;

pub(crate) fn dispatch_send_ip_fragmented_tcp(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    dispatch_with_fd::<IpFragTcpParams>(
        request,
        received_fd,
        "send_ip_fragmented_tcp requires a stream fd",
        handlers::handle_send_ip_fragmented_tcp,
    )
}

pub(crate) fn dispatch_send_ip_fragmented_udp(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    dispatch_with_fd::<IpFragUdpParams>(
        request,
        received_fd,
        "send_ip_fragmented_udp requires a socket fd",
        handlers::handle_send_ip_fragmented_udp,
    )
}

fn dispatch_with_fd<T>(
    request: &HelperRequest,
    received_fd: Option<RawFd>,
    missing_fd_error: &'static str,
    handler: fn(RawFd, T) -> (ripdpi_root_helper_protocol::HelperResponse, Option<RawFd>),
) -> DispatchOutcome
where
    T: serde::de::DeserializeOwned,
{
    let fd = match require_fd(received_fd, missing_fd_error) {
        Ok(fd) => fd,
        Err(response) => return DispatchOutcome::command(response, None),
    };

    // RAII-own the inbound SCM_RIGHTS fd so a `decode_params` failure (or any
    // early return below) closes it exactly once on drop. The handler adopts
    // the fd via `from_raw_fd` itself, so we hand it the raw integer with
    // `into_raw_fd` only once params decode — never double-closing.
    // SAFETY: `fd` was transferred to this process via SCM_RIGHTS in a single
    // dispatch frame and no other code holds a handle to it; ownership passes
    // uniquely to this `OwnedFd` until `into_raw_fd` releases it to the handler.
    let owned_fd = unsafe { OwnedFd::from_raw_fd(fd) };

    match decode_params(request) {
        Ok(params) => {
            let (response, reply_fd) = handler(owned_fd.into_raw_fd(), params);
            DispatchOutcome::command(response, reply_fd)
        }
        Err(response) => DispatchOutcome::command(response, None),
    }
}

#[cfg(test)]
mod tests {
    use std::io::Read;
    use std::net::UdpSocket;
    use std::os::fd::IntoRawFd;
    use std::os::unix::net::UnixStream;
    use std::time::Duration;

    use ripdpi_root_helper_protocol::{
        CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, HelperRequest, IpFragUdpParams, PROTOCOL_VERSION,
    };

    use super::{dispatch_send_ip_fragmented_tcp, dispatch_send_ip_fragmented_udp};

    /// Regression: an fd-carrying fragmentation command whose params fail to
    /// decode must close the inbound SCM_RIGHTS fd exactly once. Before the
    /// RAII fix the `decode_params` error arm leaked `fd`.
    #[test]
    fn decode_params_failure_closes_inbound_fd() {
        let (inbound, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
        let fd = inbound.into_raw_fd();

        // Non-null but wrong-shape params: passes descriptor validation,
        // fails `IpFragTcpParams` deserialization, never reaches the handler.
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SEND_IP_FRAGMENTED_TCP.to_string(),
            params: serde_json::json!("not-a-params-object"),
            session_nonce: None,
        };
        let outcome = dispatch_send_ip_fragmented_tcp(&request, Some(fd));

        assert!(!outcome.response.ok, "malformed params must produce an error response");
        assert!(outcome.reply_fd.is_none(), "no reply fd on a decode failure");
        assert_eq!(peer.read(&mut [0_u8]).expect("inbound descriptor must close after decode failure"), 0);
    }

    /// Regression: the UDP fragmentation handler parses `target_addr` after
    /// adopting the fd; an invalid address must still close the fd exactly
    /// once. Before the fix the early `target_addr.parse()` error returned
    /// before adoption, leaking the inbound socket descriptor.
    #[test]
    fn invalid_target_addr_closes_inbound_udp_fd() {
        let inbound = UdpSocket::bind("127.0.0.1:0").expect("Internet UDP socket");
        let address = inbound.local_addr().expect("exclusive socket address");
        assert_eq!(
            UdpSocket::bind(address).expect_err("live socket holds its port").kind(),
            std::io::ErrorKind::AddrInUse
        );
        let fd = inbound.into_raw_fd();

        // Well-formed `IpFragUdpParams` (decode succeeds, handler runs) but a
        // `target_addr` that fails to parse as a SocketAddr — the leak site.
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SEND_IP_FRAGMENTED_UDP.to_string(),
            params: serde_json::to_value(IpFragUdpParams {
                target_addr: "definitely-not-a-socket-addr".into(),
                payload: vec![1, 2, 3],
                split_offset: 1,
                default_ttl: 64,
                disorder: false,
                ipv4_identification: None,
                ipv6_hop_by_hop: false,
                ipv6_dest_opt: false,
                ipv6_dest_opt_fragmentable: false,
                ipv6_routing: false,
                ipv6_second_frag_next_override: None,
            })
            .expect("complete UDP params"),
            session_nonce: None,
        };
        let outcome = dispatch_send_ip_fragmented_udp(&request, Some(fd));

        assert!(!outcome.response.ok, "invalid target_addr must produce an error response");
        assert!(outcome.reply_fd.is_none(), "no reply fd on the parse-error path");
        assert!(outcome.response.error.as_deref().is_some_and(|error| error.starts_with("invalid target_addr:")));
        UdpSocket::bind(address).expect("closed inbound socket releases its exclusive port");
    }
}
