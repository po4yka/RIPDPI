use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use smoltcp::iface::SocketSet;
use tokio_util::sync::CancellationToken;
use tracing::info;

use crate::dns_cache::DnsCache;
use crate::io_loop::dns_intercept::DnsRequest;
use crate::io_loop::packet::TcpFlowKey;
use crate::io_loop::tcp_accept::PendingListener;
use crate::session::{Auth, TargetAddr};
use crate::split_dns::SplitDnsPolicy;
use crate::{ActiveSessions, SessionEntry, Stats};

use super::super::duplex::{create_session_duplex, create_tcp_dns_duplex};
use super::super::eviction::remove_evicted_session_socket;
use super::super::eviction::remove_pending_listen;
use super::super::target::pin_synthetic_ip;
use super::pending::PendingTcpSession;

#[allow(clippy::too_many_arguments)]
pub(super) fn admit_session(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    pending: PendingTcpSession,
    dns_req_tx: Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    split_dns_policy: Option<SplitDnsPolicy>,
) {
    let Some(mut listener) = remove_pending_listen(pending_listens, pending.handle) else {
        return;
    };
    let attribution_token = listener.take_attribution();
    pin_synthetic_ip(dns_cache, pending.synthetic_ip);

    let target = pending.target_host.as_ref().map_or(TargetAddr::Ip(pending.target_addr), |host| {
        TargetAddr::ResolvedDomain(host.clone(), pending.target_addr)
    });
    let session = if pending.dns_intercept {
        create_tcp_dns_duplex(pending.target_addr, dns_req_tx, split_dns_policy, cancel, stats)
    } else {
        create_session_duplex(
            proxy_sockaddr,
            auth,
            target,
            protect_path,
            connect_timeout,
            read_write_timeout,
            cancel,
            stats,
        )
    };
    let entry = SessionEntry {
        smoltcp_side: session.smoltcp_side,
        cancel: session.cancel,
        handle: session.handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: pending.synthetic_ip,
        attribution_token: Some(attribution_token),
    };
    let evicted = sessions.insert(pending.handle, entry);
    if let Some((_, Some(ip))) = evicted
        && let Some(cache) = dns_cache.as_mut()
    {
        cache.unpin(ip);
    }
    remove_evicted_session_socket(socket_set, evicted.map(|(handle, _)| handle));
    info!("TCP session spawned: remote={}", pending.target_addr);
}
