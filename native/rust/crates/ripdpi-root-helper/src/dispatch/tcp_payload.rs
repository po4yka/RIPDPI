use std::os::fd::{FromRawFd, IntoRawFd, OwnedFd, RawFd};

use ripdpi_root_helper_protocol::HelperRequest;

use crate::dispatch::{DispatchOutcome, decode_params, require_fd};
use crate::handlers;

pub(crate) fn dispatch_send_fake_rst(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    dispatch_with_stream_fd(request, received_fd, "send_fake_rst requires a stream fd", handlers::handle_send_fake_rst)
}

pub(crate) fn dispatch_send_fake_tcp(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    dispatch_with_stream_fd(request, received_fd, "send_fake_tcp requires a stream fd", handlers::handle_send_fake_tcp)
}

pub(crate) fn dispatch_send_flagged_tcp_payload(
    request: &HelperRequest,
    received_fd: Option<RawFd>,
) -> DispatchOutcome {
    dispatch_with_stream_fd(
        request,
        received_fd,
        "send_flagged_tcp_payload requires a stream fd",
        handlers::handle_send_flagged_tcp_payload,
    )
}

pub(crate) fn dispatch_send_seqovl_tcp(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    dispatch_with_stream_fd(
        request,
        received_fd,
        "send_seqovl_tcp requires a stream fd",
        handlers::handle_send_seqovl_tcp,
    )
}

pub(crate) fn dispatch_send_multi_disorder_tcp(request: &HelperRequest, received_fd: Option<RawFd>) -> DispatchOutcome {
    dispatch_with_stream_fd(
        request,
        received_fd,
        "send_multi_disorder_tcp requires a stream fd",
        handlers::handle_send_multi_disorder_tcp,
    )
}

pub(crate) fn dispatch_send_ordered_tcp_segments(
    request: &HelperRequest,
    received_fd: Option<RawFd>,
) -> DispatchOutcome {
    dispatch_with_stream_fd(
        request,
        received_fd,
        "send_ordered_tcp_segments requires a stream fd",
        handlers::handle_send_ordered_tcp_segments,
    )
}

fn dispatch_with_stream_fd<T>(
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
    use ripdpi_root_helper_protocol::{CMD_SEND_FAKE_RST, HelperRequest, PROTOCOL_VERSION};

    use super::dispatch_send_fake_rst;
    use crate::dispatch::test_support::{fd_is_closed, leak_owned_socket_fd};

    fn request_with_malformed_params() -> HelperRequest {
        // Non-null params (passes descriptor validation: params are "present")
        // but the wrong JSON shape for `FakeRstParams`, so `decode_params`
        // fails and the handler is never reached. This is the leak site.
        HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SEND_FAKE_RST.to_string(),
            params: serde_json::json!("not-a-params-object"),
            session_nonce: None,
        }
    }

    /// Regression: a stream-fd command whose params fail to decode must close
    /// the inbound SCM_RIGHTS fd exactly once. Before the RAII fix the
    /// `decode_params` error arm dropped the response without closing `fd`,
    /// leaking a uid-0 socket descriptor per malformed request.
    #[test]
    fn decode_params_failure_closes_inbound_stream_fd() {
        let fd = leak_owned_socket_fd();
        assert!(!fd_is_closed(fd), "freshly minted fd must start open");

        let outcome = dispatch_send_fake_rst(&request_with_malformed_params(), Some(fd));

        assert!(!outcome.response.ok, "malformed params must produce an error response");
        assert!(outcome.reply_fd.is_none(), "no reply fd on a decode failure");
        assert!(fd_is_closed(fd), "inbound fd must be closed (EBADF) after a decode failure — it leaked");
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn impossible_ordered_length_closes_the_received_socket_and_keeps_the_handler_usable() {
        use std::io::Read;
        use std::net::{TcpListener, TcpStream};
        use std::os::fd::AsFd;
        use std::os::unix::net::UnixStream;
        use std::time::Duration;

        use ripdpi_root_helper_protocol::{HelperResponse, recv_message, send_message};

        let nonce = "abcdefghijklmnopqrstuvwxyzABCDEF";
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind payload listener");
        let passed = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect payload");
        let (mut peer, _) = listener.accept().expect("accept payload");
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("set payload timeout");
        let (sender, receiver) = UnixStream::pair().expect("IPC pair");
        let request = serde_json::json!({
            "protocol_version": PROTOCOL_VERSION,
            "command": "v3/send_ordered_tcp_segments",
            "session_nonce": nonce,
            "params": {
                "segments": [{"payload": [], "ttl": 64, "sequence_offset": 0}],
                "original_payload_len": usize::MAX,
                "default_ttl": 64,
            },
        });
        let bytes = serde_json::to_vec(&request).expect("encode request");
        send_message(&sender, &bytes, Some(passed.as_fd())).expect("pass payload socket");
        drop(passed);

        crate::handle_connection(&receiver, nonce).expect("reject malformed request without unwinding");
        let (bytes, reply_fd) = recv_message(&sender, "helper closed").expect("error response");
        let response: HelperResponse = serde_json::from_slice(&bytes).expect("decode error response");
        assert!(!response.ok);
        assert!(reply_fd.is_none());
        assert_eq!(peer.read(&mut [0_u8]).expect("all received copies must close"), 0);

        let (sender, receiver) = UnixStream::pair().expect("next IPC pair");
        let request = serde_json::json!({
            "protocol_version": PROTOCOL_VERSION,
            "command": "v3/protocol_preflight",
            "session_nonce": nonce,
        });
        send_message(&sender, &serde_json::to_vec(&request).expect("encode preflight"), None).expect("send preflight");
        crate::handle_connection(&receiver, nonce).expect("handle next request");
        let (bytes, reply_fd) = recv_message(&sender, "helper closed").expect("preflight response");
        let response: HelperResponse = serde_json::from_slice(&bytes).expect("decode preflight response");
        assert!(response.ok);
        assert!(reply_fd.is_none());
    }
}
