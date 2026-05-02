use std::collections::HashMap;
use std::time::Instant as StdInstant;

use smoltcp::iface::{SocketHandle, SocketSet};
use tracing::debug;

use crate::io_loop::packet::TcpFlowKey;

use super::listener::remove_pending_listen;

pub(super) fn abort_unresolved_sessions(
    socket_set: &mut SocketSet<'static>,
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    unresolvable: Vec<SocketHandle>,
) {
    for handle in unresolvable {
        remove_pending_listen(pending_listens, handle);
        socket_set.remove(handle);
    }
}

pub(super) fn abort_unresolved_tcp_socket(handle: SocketHandle, tcp: &mut smoltcp::socket::tcp::Socket<'_>) {
    debug!("TCP socket {:?} has no resolvable target — aborting", handle);
    tcp.abort();
}
