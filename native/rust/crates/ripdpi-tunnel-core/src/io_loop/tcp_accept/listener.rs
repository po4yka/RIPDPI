use std::collections::HashMap;
use std::time::{Duration, Instant as StdInstant};

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tracing::{debug, warn};

use crate::io_loop::packet::{tcp_syn_flow_key, TcpFlowKey};
use crate::io_loop::TCP_SOCKET_BUF;

use super::socketaddr_to_listen_endpoint;

pub(crate) fn ensure_pending_listen_for_syn(
    pkt: &[u8],
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    socket_set: &mut SocketSet<'static>,
) {
    let Some(flow_key) = tcp_syn_flow_key(pkt) else {
        return;
    };
    if let std::collections::hash_map::Entry::Vacant(entry) = pending_listens.entry(flow_key) {
        let buf = || tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]);
        let mut sock = TcpSocket::new(buf(), buf());
        if sock.listen(socketaddr_to_listen_endpoint(flow_key.dst)).is_ok() {
            let handle = socket_set.add(sock);
            entry.insert((handle, StdInstant::now()));
            debug!("Added LISTEN socket for flow {} -> {}", flow_key.src, flow_key.dst);
        } else {
            warn!("listen({}) failed for flow {} -> {}", flow_key.dst.port(), flow_key.src, flow_key.dst);
        }
    }
}

pub(crate) fn gc_stale_pending_listens(
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    socket_set: &mut SocketSet<'static>,
    timeout: Duration,
) {
    let now = StdInstant::now();
    pending_listens.retain(|flow_key, (handle, created_at)| {
        let age = now.duration_since(*created_at);
        if age <= timeout {
            return true;
        }
        debug!("GC stale LISTEN socket for flow {} -> {} (age {age:?})", flow_key.src, flow_key.dst);
        socket_set.remove(*handle);
        false
    });
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
