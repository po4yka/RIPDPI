use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use smoltcp::iface::SocketSet;
use tokio_util::sync::CancellationToken;

use crate::dns_cache::DnsCache;
use crate::io_loop::dns_intercept::{DnsRequest, MapDnsRuntime};
use crate::io_loop::packet::TcpFlowKey;
use crate::io_loop::tcp_accept::PendingListener;
use crate::session::Auth;
use crate::split_dns::SplitDnsPolicy;
use crate::uid_policy::UidFlowPolicy;
use crate::{ActiveSessions, Stats};

use super::unresolved::abort_unresolved_sessions;

mod batch;
mod collect;
mod pending;
mod session;

use collect::{AdmissionInputs, collect_admissible_sessions};
use session::admit_session;

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_new_tcp_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    pending_listens: &mut HashMap<TcpFlowKey, PendingListener>,
    admission_cursor: &mut usize,
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    active_direct_generation: Option<&mut Option<u64>>,
    uid_policy: &UidFlowPolicy,
    mapdns_runtime: Option<MapDnsRuntime>,
    dns_req_tx: Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    split_dns_policy: Option<SplitDnsPolicy>,
) -> Vec<smoltcp::iface::SocketHandle> {
    let (new_sessions, unresolvable) = collect_admissible_sessions(
        socket_set,
        sessions,
        pending_listens,
        admission_cursor,
        dns_cache,
        active_direct_generation,
        AdmissionInputs { stats, uid_policy, mapdns_runtime },
    );
    let resetting = abort_unresolved_sessions(pending_listens, unresolvable);

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
            dns_req_tx.clone(),
            split_dns_policy.clone(),
        );
    }
    resetting
}
