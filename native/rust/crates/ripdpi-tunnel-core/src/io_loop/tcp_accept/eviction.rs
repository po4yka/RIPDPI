use std::collections::HashMap;

use smoltcp::iface::{SocketHandle, SocketSet};
use tracing::debug;

use crate::io_loop::packet::TcpFlowKey;
use crate::io_loop::tcp_accept::PendingListener;

pub(super) fn remove_evicted_session_socket(socket_set: &mut SocketSet<'static>, evicted_handle: Option<SocketHandle>) {
    if let Some(evicted_handle) = evicted_handle {
        socket_set.remove(evicted_handle);
        debug!("Evicted session socket {:?} removed from socket_set", evicted_handle);
    }
}

pub(super) fn evict_oldest_pending_listen(
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    socket_set: &mut SocketSet<'static>,
) {
    let oldest = pending_listens
        .iter()
        .min_by_key(|(key, pending)| (pending.created_at, key.src, key.dst))
        .map(|(key, pending)| (*key, pending.handle));
    if let Some((flow_key, handle)) = oldest {
        pending_listens.remove(&flow_key);
        socket_set.remove(handle);
        debug!("Evicted oldest pending LISTEN socket for flow {} -> {}", flow_key.src, flow_key.dst);
    }
}

pub(super) fn remove_pending_listen(
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    handle: SocketHandle,
) -> Option<PendingListener> {
    let key = pending_listens.iter().find_map(|(key, pending)| (pending.handle == handle).then_some(*key))?;
    pending_listens.remove(&key)
}
