use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use std::time::Instant as StdInstant;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
use crate::session::Auth;
use crate::uid_policy::{CachedFlowUidSource, PROTO_TCP, UidFlowPolicy, Verdict};
use crate::{ActiveSessions, Stats};

use super::target::{pinned_synthetic_ip, tcp_session_target_addr};
use super::unresolved::{abort_unresolved_sessions, abort_unresolved_tcp_socket};

mod pending;
mod session;

use pending::PendingTcpSession;
use session::admit_session;

const TCP_ADMISSION_WORK_BUDGET: usize = 64;

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_new_tcp_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    admission_cursor: &mut usize,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    uid_policy: &UidFlowPolicy,
) {
    let (new_sessions, unresolvable) = collect_admissible_sessions(
        socket_set,
        sessions,
        pending_listens,
        admission_cursor,
        stats,
        dns_cache,
        uid_policy,
    );
    abort_unresolved_sessions(socket_set, pending_listens, unresolvable);

    for pending in new_sessions {
        admit_session(
            socket_set,
            sessions,
            pending_listens,
            proxy_sockaddr,
            auth,
            protect_path,
            connect_timeout,
            read_write_timeout,
            cancel,
            stats,
            dns_cache,
            pending,
        );
    }
}

fn collect_admissible_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &ActiveSessions,
    pending_listens: &HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    admission_cursor: &mut usize,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    uid_policy: &UidFlowPolicy,
) -> (Vec<PendingTcpSession>, Vec<SocketHandle>) {
    let mut new_sessions = Vec::new();
    let mut unresolvable = Vec::new();
    let pending_count = pending_listens.len();
    if pending_count == 0 {
        *admission_cursor = 0;
        return (new_sessions, unresolvable);
    }
    let handles = pending_handle_batch(pending_listens, admission_cursor, TCP_ADMISSION_WORK_BUDGET);

    for handle in handles {
        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        if !tcp.may_send() || sessions.contains(handle) {
            continue;
        }

        let synthetic_ip = pinned_synthetic_ip(dns_cache, tcp);
        match tcp_session_target_addr(stats, dns_cache, tcp) {
            Some(target_addr) => {
                // Record the originating app's flow for per-app attribution. This is the one site that sees both the app source (smoltcp remote endpoint) and the intercepted destination. `note_flow` only locks a mutex and pushes an exact-tuple job — never any JNI on this hot path; a background worker resolves off-path.
                let Some(app_src) = tcp.remote_endpoint().map(endpoint_to_socketaddr) else {
                    if uid_policy.is_enforcing() {
                        abort_unresolved_tcp_socket(handle, tcp);
                        unresolvable.push(handle);
                        continue;
                    }
                    new_sessions.push(PendingTcpSession { handle, target_addr, synthetic_ip, attribution_token: None });
                    continue;
                };
                let observation = ripdpi_flow_app_attribution::note_flow(PROTO_TCP, app_src, target_addr);
                match uid_policy.admit(&CachedFlowUidSource, PROTO_TCP, app_src, target_addr) {
                    Verdict::Allow => {}
                    Verdict::Pending => continue,
                    Verdict::ResetTcp | Verdict::DropUdp => {
                        ripdpi_flow_app_attribution::evict_flow(observation.token);
                        abort_unresolved_tcp_socket(handle, tcp);
                        unresolvable.push(handle);
                        continue;
                    }
                }
                new_sessions.push(PendingTcpSession {
                    handle,
                    target_addr,
                    synthetic_ip,
                    attribution_token: Some(observation.token),
                });
            }
            None => {
                abort_unresolved_tcp_socket(handle, tcp);
                unresolvable.push(handle);
            }
        }
    }

    (new_sessions, unresolvable)
}

fn pending_handle_batch(
    pending_listens: &HashMap<TcpFlowKey, (SocketHandle, StdInstant)>,
    admission_cursor: &mut usize,
    budget: usize,
) -> Vec<SocketHandle> {
    let pending_count = pending_listens.len();
    if pending_count == 0 {
        *admission_cursor = 0;
        return Vec::new();
    }
    let start = *admission_cursor % pending_count;
    let handles: Vec<_> = pending_listens
        .values()
        .skip(start)
        .chain(pending_listens.values().take(start))
        .take(budget.min(pending_count))
        .map(|(handle, _)| *handle)
        .collect();
    *admission_cursor = (start + handles.len()) % pending_count;
    handles
}

#[cfg(test)]
mod tests {
    use std::collections::{HashMap, HashSet};
    use std::net::{Ipv4Addr, SocketAddr};
    use std::time::Instant;

    use smoltcp::iface::SocketSet;
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};

    use super::{TCP_ADMISSION_WORK_BUDGET, TcpFlowKey, pending_handle_batch};

    #[test]
    fn pending_admission_batches_are_bounded_and_rotate_across_all_handles() {
        let mut socket_set = SocketSet::new(vec![]);
        let mut pending = HashMap::new();
        for index in 0..130u16 {
            let socket = TcpSocket::new(tcp::SocketBuffer::new(vec![0; 1]), tcp::SocketBuffer::new(vec![0; 1]));
            let handle = socket_set.add(socket);
            let key = TcpFlowKey {
                src: SocketAddr::new(Ipv4Addr::LOCALHOST.into(), 10_000 + index),
                dst: SocketAddr::new(Ipv4Addr::new(203, 0, 113, 1).into(), 443),
            };
            pending.insert(key, (handle, Instant::now()));
        }
        let mut cursor = 0;
        let mut seen = HashSet::new();

        for _ in 0..3 {
            let batch = pending_handle_batch(&pending, &mut cursor, TCP_ADMISSION_WORK_BUDGET);
            assert_eq!(batch.len(), TCP_ADMISSION_WORK_BUDGET);
            seen.extend(batch);
        }

        assert_eq!(seen.len(), pending.len(), "rotating batches must eventually visit every pending socket");
    }
}
