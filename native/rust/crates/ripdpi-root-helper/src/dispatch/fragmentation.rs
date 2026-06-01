use std::os::fd::RawFd;

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

    match decode_params(request) {
        Ok(params) => {
            let (response, reply_fd) = handler(fd, params);
            DispatchOutcome::command(response, reply_fd)
        }
        Err(response) => DispatchOutcome::command(response, None),
    }
}
