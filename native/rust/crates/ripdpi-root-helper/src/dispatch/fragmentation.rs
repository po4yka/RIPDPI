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
    use ripdpi_root_helper_protocol::{
        CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, HelperRequest, PROTOCOL_VERSION,
    };

    use super::{dispatch_send_ip_fragmented_tcp, dispatch_send_ip_fragmented_udp};
    use crate::dispatch::test_support::{fd_is_closed, leak_owned_socket_fd};

    /// Regression: an fd-carrying fragmentation command whose params fail to
    /// decode must close the inbound SCM_RIGHTS fd exactly once. Before the
    /// RAII fix the `decode_params` error arm leaked `fd`.
    #[test]
    fn decode_params_failure_closes_inbound_fd() {
        let fd = leak_owned_socket_fd();
        assert!(!fd_is_closed(fd), "freshly minted fd must start open");

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
        assert!(fd_is_closed(fd), "inbound fd must be closed (EBADF) after a decode failure — it leaked");
    }

    /// Regression: the UDP fragmentation handler parses `target_addr` after
    /// adopting the fd; an invalid address must still close the fd exactly
    /// once. Before the fix the early `target_addr.parse()` error returned
    /// before adoption, leaking the inbound socket descriptor.
    #[test]
    fn invalid_target_addr_closes_inbound_udp_fd() {
        let fd = leak_owned_socket_fd();
        assert!(!fd_is_closed(fd), "freshly minted fd must start open");

        // Well-formed `IpFragUdpParams` (decode succeeds, handler runs) but a
        // `target_addr` that fails to parse as a SocketAddr — the leak site.
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SEND_IP_FRAGMENTED_UDP.to_string(),
            params: serde_json::json!({
                "target_addr": "definitely-not-a-socket-addr",
                "payload": [1, 2, 3],
                "split_offset": 1,
                "default_ttl": 64,
            }),
            session_nonce: None,
        };
        let outcome = dispatch_send_ip_fragmented_udp(&request, Some(fd));

        assert!(!outcome.response.ok, "invalid target_addr must produce an error response");
        assert!(outcome.reply_fd.is_none(), "no reply fd on the parse-error path");
        assert!(fd_is_closed(fd), "inbound fd must be closed (EBADF) after a target_addr parse failure — it leaked");
    }
}
