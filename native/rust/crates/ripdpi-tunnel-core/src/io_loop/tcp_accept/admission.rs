use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant as StdInstant;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::Socket;
use tokio_util::sync::CancellationToken;
use tracing::info;

use crate::dns_cache::DnsCache;
use crate::io_loop::packet::TcpFlowKey;
use crate::session::Auth;
use crate::{ActiveSessions, SessionEntry, Stats};

use super::duplex::create_session_duplex;
use super::eviction::remove_evicted_session_socket;
use super::listener::remove_pending_listen;
use super::target::{pin_synthetic_ip, pinned_synthetic_ip, tcp_session_target_addr};
use super::unresolved::{abort_unresolved_sessions, abort_unresolved_tcp_socket};

struct PendingTcpSession {
    handle: SocketHandle,
    target_addr: SocketAddr,
    synthetic_ip: Option<u32>,
}

pub(crate) fn spawn_new_tcp_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
) {
    let (new_sessions, unresolvable) = collect_admissible_sessions(socket_set, sessions, stats, dns_cache);
    abort_unresolved_sessions(socket_set, pending_listens, unresolvable);

    for pending in new_sessions {
        admit_session(socket_set, sessions, pending_listens, proxy_sockaddr, auth, cancel, stats, dns_cache, pending);
    }
}

fn collect_admissible_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &ActiveSessions,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
) -> (Vec<PendingTcpSession>, Vec<SocketHandle>) {
    let mut new_sessions = Vec::new();
    let mut unresolvable = Vec::new();

    for (handle, socket) in socket_set.iter_mut() {
        let Socket::Tcp(tcp) = socket else { continue };
        if !tcp.may_send() || sessions.contains(handle) {
            continue;
        }

        let synthetic_ip = pinned_synthetic_ip(dns_cache, tcp);
        match tcp_session_target_addr(stats, dns_cache, tcp) {
            Some(target_addr) => new_sessions.push(PendingTcpSession { handle, target_addr, synthetic_ip }),
            None => {
                abort_unresolved_tcp_socket(handle, tcp);
                unresolvable.push(handle);
            }
        }
    }

    (new_sessions, unresolvable)
}

#[allow(clippy::too_many_arguments)]
fn admit_session(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    pending: PendingTcpSession,
) {
    remove_pending_listen(pending_listens, pending.handle);
    pin_synthetic_ip(dns_cache, pending.synthetic_ip);

    let session = create_session_duplex(proxy_sockaddr, auth, pending.target_addr, cancel, stats);
    let entry = SessionEntry {
        smoltcp_side: session.smoltcp_side,
        cancel: session.cancel,
        handle: session.handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: pending.synthetic_ip,
    };
    let evicted_handle = sessions.insert(pending.handle, entry);
    remove_evicted_session_socket(socket_set, evicted_handle);
    info!("TCP session spawned: remote={}", pending.target_addr);
}
