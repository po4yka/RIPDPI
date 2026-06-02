use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant as StdInstant;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::Socket;
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
use crate::session::Auth;
use crate::{ActiveSessions, Stats};

use super::target::{pinned_synthetic_ip, tcp_session_target_addr};
use super::unresolved::{abort_unresolved_sessions, abort_unresolved_tcp_socket};

mod pending;
mod session;

use pending::PendingTcpSession;
use session::admit_session;

/// IANA IP protocol number for TCP, for flow-attribution `note_flow`.
const PROTO_TCP: u8 = 6;

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
            Some(target_addr) => {
                // Record the originating app's flow for per-app attribution. This is
                // the one site that sees both the app source (smoltcp remote
                // endpoint) and the intercepted destination. `note_flow` only locks
                // a mutex and pushes to a queue (deduped by destination) — never any
                // JNI on this hot path; a background worker resolves off-path.
                if let Some(app_src) = tcp.remote_endpoint().map(endpoint_to_socketaddr) {
                    ripdpi_flow_app_attribution::note_flow(PROTO_TCP, app_src, target_addr);
                }
                new_sessions.push(PendingTcpSession { handle, target_addr, synthetic_ip });
            }
            None => {
                abort_unresolved_tcp_socket(handle, tcp);
                unresolvable.push(handle);
            }
        }
    }

    (new_sessions, unresolvable)
}
