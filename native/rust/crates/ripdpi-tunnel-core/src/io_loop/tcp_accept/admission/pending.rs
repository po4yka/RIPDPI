use std::net::SocketAddr;

use smoltcp::iface::SocketHandle;

pub(super) struct PendingTcpSession {
    pub(super) handle: SocketHandle,
    pub(super) target_addr: SocketAddr,
    pub(super) target_host: Option<String>,
    pub(super) synthetic_ip: Option<u32>,
    pub(super) attribution_token: Option<ripdpi_flow_app_attribution::FlowAttributionToken>,
    pub(super) dns_intercept: bool,
}
