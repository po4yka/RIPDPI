use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use std::time::Instant as StdInstant;

use smoltcp::iface::{SocketHandle, SocketSet};
use tokio_util::sync::CancellationToken;
use tracing::info;

use crate::dns_cache::DnsCache;
use crate::io_loop::packet::TcpFlowKey;
use crate::session::Auth;
use crate::{ActiveSessions, SessionEntry, Stats};

use super::super::duplex::create_session_duplex;
use super::super::eviction::remove_evicted_session_socket;
use super::super::eviction::remove_pending_listen;
use super::super::target::pin_synthetic_ip;
use super::PendingTcpSession;

#[allow(clippy::too_many_arguments)]
pub(super) fn admit_session(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    pending: PendingTcpSession,
) {
    remove_pending_listen(pending_listens, pending.handle);
    pin_synthetic_ip(dns_cache, pending.synthetic_ip);

    let session = create_session_duplex(
        proxy_sockaddr,
        auth,
        pending.target_addr,
        protect_path,
        connect_timeout,
        read_write_timeout,
        cancel,
        stats,
    );
    let entry = SessionEntry {
        smoltcp_side: session.smoltcp_side,
        cancel: session.cancel,
        handle: session.handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: pending.synthetic_ip,
        target_addr: pending.target_addr,
    };
    let evicted_handle = sessions.insert(pending.handle, entry);
    remove_evicted_session_socket(socket_set, evicted_handle);
    info!("TCP session spawned: remote={}", pending.target_addr);
}
