use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::dns_cache::DnsCache;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
use crate::uid_policy::{CachedFlowUidSource, PROTO_TCP, UidFlowPolicy, Verdict};
use crate::{ActiveSessions, Stats};

use super::super::target::{pinned_synthetic_ip, tcp_session_target_addr};
use super::super::tcp_target_endpoint;
use super::super::unresolved::abort_unresolved_tcp_socket;
use super::batch::{TCP_ADMISSION_WORK_BUDGET, pending_handle_batch};
use super::pending::PendingTcpSession;

pub(super) fn collect_admissible_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &ActiveSessions,
    pending_listens: &HashMap<TcpFlowKey, (SocketHandle, Instant)>,
    admission_cursor: &mut usize,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    mut active_direct_generation: Option<&mut Option<u64>>,
    uid_policy: &UidFlowPolicy,
) -> (Vec<PendingTcpSession>, Vec<SocketHandle>) {
    let mut new_sessions = Vec::new();
    let mut unresolvable = Vec::new();
    if pending_listens.is_empty() {
        *admission_cursor = 0;
        return (new_sessions, unresolvable);
    }
    let handles = pending_handle_batch(pending_listens, admission_cursor, TCP_ADMISSION_WORK_BUDGET);
    for handle in handles {
        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        if !tcp.may_send() || sessions.contains(handle) {
            continue;
        }
        // Android's owner-UID lookup must use the tuple the kernel sees. For
        // MapDNS flows that is the original synthetic destination, while the
        // SOCKS session must receive the separately resolved real target.
        let attribution_remote = tcp_target_endpoint(tcp);
        let synthetic_ip = pinned_synthetic_ip(dns_cache, tcp);
        match tcp_session_target_addr(stats, dns_cache, active_direct_generation.as_deref_mut(), tcp) {
            Some(target_addr) => collect_resolved_session(
                handle,
                tcp,
                target_addr,
                attribution_remote.unwrap_or(target_addr),
                synthetic_ip,
                uid_policy,
                &mut new_sessions,
                &mut unresolvable,
            ),
            None => {
                abort_unresolved_tcp_socket(handle, tcp);
                unresolvable.push(handle);
            }
        }
    }
    (new_sessions, unresolvable)
}

fn collect_resolved_session(
    handle: SocketHandle,
    tcp: &mut TcpSocket<'_>,
    target_addr: std::net::SocketAddr,
    attribution_remote: std::net::SocketAddr,
    synthetic_ip: Option<u32>,
    uid_policy: &UidFlowPolicy,
    new_sessions: &mut Vec<PendingTcpSession>,
    unresolvable: &mut Vec<SocketHandle>,
) {
    // This is the one site that sees both the app source and intercepted destination; `note_flow` only queues exact-tuple attribution work and never invokes JNI on this hot path.
    let Some(app_src) = tcp.remote_endpoint().map(endpoint_to_socketaddr) else {
        if uid_policy.is_enforcing() {
            abort_unresolved_tcp_socket(handle, tcp);
            unresolvable.push(handle);
        } else {
            new_sessions.push(PendingTcpSession { handle, target_addr, synthetic_ip, attribution_token: None });
        }
        return;
    };
    let observation = ripdpi_flow_app_attribution::note_flow(PROTO_TCP, app_src, attribution_remote);
    match uid_policy.admit(&CachedFlowUidSource, PROTO_TCP, app_src, attribution_remote) {
        Verdict::Allow => new_sessions.push(PendingTcpSession {
            handle,
            target_addr,
            synthetic_ip,
            attribution_token: Some(observation.token),
        }),
        Verdict::Pending => {}
        Verdict::ResetTcp | Verdict::DropUdp => {
            ripdpi_flow_app_attribution::evict_flow(observation.token);
            abort_unresolved_tcp_socket(handle, tcp);
            unresolvable.push(handle);
        }
    }
}
