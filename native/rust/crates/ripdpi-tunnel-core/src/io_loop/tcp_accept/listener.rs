use std::collections::HashMap;
use std::time::Instant as StdInstant;

use ripdpi_flow_app_attribution::FlowAttributionToken;
use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::AnySocket;
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tracing::{debug, warn};

use crate::io_loop::TCP_SOCKET_BUF;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr, tcp_syn_flow_key};

use super::eviction::evict_oldest_pending_listen;
use super::socketaddr_to_listen_endpoint;

mod maintenance;

pub(crate) use maintenance::{gc_stale_pending_listens, reconcile_pending_listeners};

/// Each pending TCP socket owns two `TCP_SOCKET_BUF` allocations. Keep the half-open handshake budget at roughly 16 MiB on Android.
const MAX_PENDING_LISTENS: usize = 128;

pub(crate) struct PendingListener {
    pub(crate) handle: SocketHandle,
    pub(crate) created_at: StdInstant,
    attribution: PendingAttribution,
}

/// The listener owns the registration until admission transfers it to a session.
struct PendingAttribution(Option<FlowAttributionToken>);

impl Drop for PendingAttribution {
    fn drop(&mut self) {
        if let Some(token) = self.0.take() {
            ripdpi_flow_app_attribution::evict_flow(token);
        }
    }
}

impl PendingListener {
    pub(crate) fn new(handle: SocketHandle, key: TcpFlowKey) -> Self {
        let token = ripdpi_flow_app_attribution::note_flow(crate::uid_policy::PROTO_TCP, key.src, key.dst).token;
        Self { handle, created_at: StdInstant::now(), attribution: PendingAttribution(Some(token)) }
    }

    pub(crate) fn attribution_token(&self) -> &FlowAttributionToken {
        // Infallible: only admission takes the token, after removing this listener from the pending map.
        self.attribution.0.as_ref().expect("pending listener owns its attribution")
    }

    pub(crate) fn take_attribution(&mut self) -> FlowAttributionToken {
        // Infallible: admission removes the unique listener and calls this exactly once before dropping it.
        self.attribution.0.take().expect("attribution transfers once at admission")
    }
}

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
