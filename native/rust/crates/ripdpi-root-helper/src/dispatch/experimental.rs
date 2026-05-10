use ripdpi_privileged_ops::{IcmpWrappedUdpRecvFilter, IcmpWrappedUdpSpec, SynHideTcpSpec};
use ripdpi_root_helper_protocol::HelperRequest;
use ripdpi_root_helper_protocol::RawIpPacketParams;

use crate::dispatch::{decode_params, DispatchOutcome};
use crate::handlers;

pub(crate) fn dispatch_send_syn_hide_tcp(request: &HelperRequest) -> DispatchOutcome {
    dispatch_without_fd::<SynHideTcpSpec>(request, handlers::handle_send_syn_hide_tcp)
}

pub(crate) fn dispatch_send_icmp_wrapped_udp(request: &HelperRequest) -> DispatchOutcome {
    dispatch_without_fd::<IcmpWrappedUdpSpec>(request, handlers::handle_send_icmp_wrapped_udp)
}

pub(crate) fn dispatch_recv_icmp_wrapped_udp(request: &HelperRequest) -> DispatchOutcome {
    dispatch_without_fd::<IcmpWrappedUdpRecvFilter>(request, handlers::handle_recv_icmp_wrapped_udp)
}

pub(crate) fn dispatch_send_raw_ip_packet(request: &HelperRequest) -> DispatchOutcome {
    dispatch_without_fd::<RawIpPacketParams>(request, handlers::handle_send_raw_ip_packet)
}

fn dispatch_without_fd<T>(
    request: &HelperRequest,
    handler: fn(T) -> (ripdpi_root_helper_protocol::HelperResponse, Option<std::os::fd::RawFd>),
) -> DispatchOutcome
where
    T: serde::de::DeserializeOwned,
{
    match decode_params(request) {
        Ok(params) => {
            let (response, reply_fd) = handler(params);
            DispatchOutcome::command(response, reply_fd)
        }
        Err(response) => DispatchOutcome::command(response, None),
    }
}
