use std::collections::HashMap;
use std::time::Instant as StdInstant;

use smoltcp::iface::SocketHandle;
use tracing::debug;

use crate::io_loop::packet::TcpFlowKey;

use super::eviction::remove_pending_listen;

pub(super) fn abort_unresolved_sessions(
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    unresolvable: Vec<SocketHandle>,
) -> Vec<SocketHandle> {
    for &handle in &unresolvable {
        remove_pending_listen(pending_listens, handle);
    }
    unresolvable
}

pub(super) fn abort_unresolved_tcp_socket(handle: SocketHandle, tcp: &mut smoltcp::socket::tcp::Socket<'_>) {
    debug!("TCP socket {:?} has no resolvable target — aborting", handle);
    tcp.abort();
}
