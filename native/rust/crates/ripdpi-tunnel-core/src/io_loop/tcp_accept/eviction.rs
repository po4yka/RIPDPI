use std::collections::HashMap;
use std::time::Instant as StdInstant;

use smoltcp::iface::{SocketHandle, SocketSet};
use tracing::debug;

use crate::io_loop::packet::TcpFlowKey;

pub(super) fn remove_evicted_session_socket(socket_set: &mut SocketSet<'static>, evicted_handle: Option<SocketHandle>) {
    if let Some(evicted_handle) = evicted_handle {
        socket_set.remove(evicted_handle);
        debug!("Evicted session socket {:?} removed from socket_set", evicted_handle);
    }
}

pub(super) fn evict_oldest_pending_listen(
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    socket_set: &mut SocketSet<'static>,
) {
    let oldest = pending_listens
        .iter()
        .min_by_key(|(key, (_, created_at))| (*created_at, key.src, key.dst))
        .map(|(key, (handle, _))| (*key, *handle));
    if let Some((flow_key, handle)) = oldest {
        pending_listens.remove(&flow_key);
        socket_set.remove(handle);
        debug!("Evicted oldest pending LISTEN socket for flow {} -> {}", flow_key.src, flow_key.dst);
    }
}

pub(super) fn remove_pending_listen(
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    handle: SocketHandle,
) {
    if let Some(key) =
        pending_listens.iter().find_map(|(key, (pending_handle, _))| (*pending_handle == handle).then_some(*key))
    {
        pending_listens.remove(&key);
    }
}
