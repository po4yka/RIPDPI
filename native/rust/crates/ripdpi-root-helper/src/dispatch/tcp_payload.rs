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
}
