use std::net::SocketAddr;

use smoltcp::iface::SocketHandle;

pub(super) struct PendingTcpSession {
    pub(super) handle: SocketHandle,
    pub(super) target_addr: SocketAddr,
    pub(super) synthetic_ip: Option<u32>,
}
