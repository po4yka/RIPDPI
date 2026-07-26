use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::dns_cache::DnsCache;
use crate::io_loop::dns_intercept::MapDnsRuntime;
use crate::io_loop::dns_intercept::ResolvedMappedTarget;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
use crate::uid_policy::{CachedFlowUidSource, PROTO_TCP, UidFlowPolicy, Verdict};
use crate::{ActiveSessions, Stats};

use super::super::target::{pinned_synthetic_ip, tcp_session_target};
use super::super::tcp_target_endpoint;
use super::super::unresolved::abort_unresolved_tcp_socket;
use super::batch::{TCP_ADMISSION_WORK_BUDGET, pending_handle_batch};
use super::pending::PendingTcpSession;

pub(super) struct AdmissionInputs<'a> {
    pub(super) stats: &'a Arc<Stats>,
    pub(super) uid_policy: &'a UidFlowPolicy,
    pub(super) mapdns_runtime: Option<MapDnsRuntime>,
}

struct AdmissionTarget {
    resolved: ResolvedMappedTarget,
    synthetic_ip: Option<u32>,
    dns_intercept: bool,
}

pub(super) fn collect_admissible_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &ActiveSessions,
    pending_listens: &HashMap<TcpFlowKey, (SocketHandle, Instant)>,
    admission_cursor: &mut usize,
    dns_cache: &mut Option<DnsCache>,
    mut active_direct_generation: Option<&mut Option<u64>>,
    inputs: AdmissionInputs<'_>,
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
        let dns_intercept = attribution_remote
            .is_some_and(|target| inputs.mapdns_runtime.is_some_and(|mapdns| target == mapdns.intercept_addr));
        if dns_intercept {
            let intercept_addr = attribution_remote.expect("DNS intercept endpoint checked above");
            let target = ResolvedMappedTarget { addr: intercept_addr, host: None };
            collect_resolved_session(
                handle,
                tcp,
                AdmissionTarget { resolved: target, synthetic_ip: None, dns_intercept: true },
                intercept_addr,
                inputs.uid_policy,
                &mut new_sessions,
                &mut unresolvable,
            );
            continue;
        }
        match tcp_session_target(inputs.stats, dns_cache, active_direct_generation.as_deref_mut(), tcp) {
            Some(target) => {
                let attribution_remote = attribution_remote.unwrap_or(target.addr);
                collect_resolved_session(
                    handle,
                    tcp,
                    AdmissionTarget { resolved: target, synthetic_ip, dns_intercept: false },
                    attribution_remote,
                    inputs.uid_policy,
                    &mut new_sessions,
                    &mut unresolvable,
                );
            }
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
    target: AdmissionTarget,
    attribution_remote: std::net::SocketAddr,
    uid_policy: &UidFlowPolicy,
    new_sessions: &mut Vec<PendingTcpSession>,
    unresolvable: &mut Vec<SocketHandle>,
) {
    let AdmissionTarget { resolved, synthetic_ip, dns_intercept } = target;
    let ResolvedMappedTarget { addr: target_addr, host: target_host } = resolved;
    // This is the one site that sees both the app source and intercepted destination; `note_flow` only queues exact-tuple attribution work and never invokes JNI on this hot path.
    let Some(app_src) = tcp.remote_endpoint().map(endpoint_to_socketaddr) else {
        if uid_policy.is_enforcing() {
            abort_unresolved_tcp_socket(handle, tcp);
            unresolvable.push(handle);
        } else {
            new_sessions.push(PendingTcpSession {
                handle,
                target_addr,
                target_host,
                synthetic_ip,
                attribution_token: None,
                dns_intercept,
            });
        }
        return;
    };
    let observation = ripdpi_flow_app_attribution::note_flow(PROTO_TCP, app_src, attribution_remote);
    match uid_policy.admit(&CachedFlowUidSource, PROTO_TCP, app_src, attribution_remote) {
        Verdict::Allow => new_sessions.push(PendingTcpSession {
            handle,
            target_addr,
            target_host,
            synthetic_ip,
            attribution_token: Some(observation.token),
            dns_intercept,
        }),
        Verdict::Pending => {}
        Verdict::ResetTcp | Verdict::DropUdp => {
            ripdpi_flow_app_attribution::evict_flow(observation.token);
            abort_unresolved_tcp_socket(handle, tcp);
            unresolvable.push(handle);
        }
    }
}
