use std::collections::HashMap;

use smoltcp::iface::SocketSet;
use smoltcp::socket::AnySocket;
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tracing::{debug, warn};

use crate::io_loop::TCP_SOCKET_BUF;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr, tcp_syn_flow_key};

use super::eviction::evict_oldest_pending_listen;
use super::socketaddr_to_listen_endpoint;

mod maintenance;
mod pending;

pub(crate) use maintenance::{gc_stale_pending_listens, reconcile_pending_listeners};
pub(crate) use pending::PendingListener;

/// Each pending TCP socket owns two `TCP_SOCKET_BUF` allocations. Keep the half-open handshake budget at roughly 16 MiB on Android.
const MAX_PENDING_LISTENS: usize = 128;

pub(crate) fn ensure_pending_listen_for_syn(
    pkt: &[u8],
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    socket_set: &mut SocketSet<'static>,
) {
    let Some(flow_key) = tcp_syn_flow_key(pkt) else {
        return;
    };
    if pending_listens.contains_key(&flow_key) {
        return;
    }
    // Retransmitted SYNs must not create another owner of an active generation.
    if socket_set.iter().any(|(_, socket)| {
        TcpSocket::downcast(socket).is_some_and(|tcp| {
            tcp.remote_endpoint().map(endpoint_to_socketaddr) == Some(flow_key.src)
                && tcp.local_endpoint().map(endpoint_to_socketaddr) == Some(flow_key.dst)
        })
    }) {
        return;
    }

    let buf = || tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]);
    let mut sock = TcpSocket::new(buf(), buf());
    if sock.listen(socketaddr_to_listen_endpoint(flow_key.dst)).is_err() {
        warn!("listen({}) failed for flow {} -> {}", flow_key.dst.port(), flow_key.src, flow_key.dst);
        return;
    }

    if pending_listens.len() >= MAX_PENDING_LISTENS {
        evict_oldest_pending_listen(pending_listens, socket_set);
    }

    let handle = socket_set.add(sock);
    pending_listens.insert(flow_key, PendingListener::new(handle, flow_key));
    debug!("Added LISTEN socket for flow {} -> {}", flow_key.src, flow_key.dst);
}
