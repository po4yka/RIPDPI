use std::collections::HashMap;
use std::sync::Arc;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::dns_cache::DnsCache;
use crate::io_loop::dns_intercept::MapDnsRuntime;
use crate::io_loop::dns_intercept::ResolvedMappedTarget;
use crate::io_loop::packet::{TcpFlowKey, endpoint_to_socketaddr};
use crate::io_loop::tcp_accept::PendingListener;
use crate::uid_policy::{UidFlowPolicy, Verdict};
use crate::{ActiveSessions, Stats};

use super::super::listener::reconcile_pending_listeners;
use super::super::target::{pinned_synthetic_ip, tcp_session_target};
use super::super::tcp_target_endpoint;
use super::super::unresolved::abort_unresolved_tcp_socket;
use super::batch::{TCP_ADMISSION_WORK_BUDGET, pending_listener_batch};
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
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
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
    reconcile_pending_listeners(pending_listens, socket_set);
    let listeners = pending_listener_batch(pending_listens, admission_cursor, TCP_ADMISSION_WORK_BUDGET);
    for listener in listeners {
        let handle = listener.handle;
        let registration_id = listener.attribution_id();
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
                registration_id,
                inputs.uid_policy,
                &mut new_sessions,
                &mut unresolvable,
            );
            continue;
        }
        match tcp_session_target(inputs.stats, dns_cache, active_direct_generation.as_deref_mut(), tcp) {
            Some(target) => {
                collect_resolved_session(
                    handle,
                    tcp,
                    AdmissionTarget { resolved: target, synthetic_ip, dns_intercept: false },
                    registration_id,
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
    registration_id: &ripdpi_flow_app_attribution::FlowRegistrationId,
    uid_policy: &UidFlowPolicy,
    new_sessions: &mut Vec<PendingTcpSession>,
    unresolvable: &mut Vec<SocketHandle>,
) {
    let AdmissionTarget { resolved, synthetic_ip, dns_intercept } = target;
    let ResolvedMappedTarget { addr: target_addr, host: target_host } = resolved;
    // The registration belongs to this exact pre-handshake listener. Never
    // create a fresh generation at admission after its original lookup expired.
    let request = registration_id.request();
    if tcp.remote_endpoint().map(endpoint_to_socketaddr) != Some(request.local)
        || tcp_target_endpoint(tcp) != Some(request.remote)
    {
        abort_unresolved_tcp_socket(handle, tcp);
        unresolvable.push(handle);
        return;
    }
    match uid_policy.admit_registration(registration_id) {
        Verdict::Allow => {
            new_sessions.push(PendingTcpSession { handle, target_addr, target_host, synthetic_ip, dns_intercept });
        }
        Verdict::Pending => {}
        Verdict::ResetTcp | Verdict::DropUdp => {
            abort_unresolved_tcp_socket(handle, tcp);
            unresolvable.push(handle);
        }
    }
}
