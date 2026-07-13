use std::io;

use smoltcp::iface::{SocketHandle, SocketSet};
use tokio::io::AsyncWriteExt;
use tokio::task::JoinHandle;

use crate::ActiveSessions;
use crate::dns_cache::DnsCache;

mod socket_removal;

use socket_removal::remove_tcp_socket;

pub(super) async fn remove_session(
    handle: SocketHandle,
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) {
    if let Some(task) = take_session_task(handle, sessions, socket_set, dns_cache).await {
        task.abort();
    }
}

/// NOT cancel-safe: the returned task handle must be drained or aborted by the caller.
pub(super) async fn take_session_task(
    handle: SocketHandle,
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) -> Option<JoinHandle<io::Result<()>>> {
    let mut task = None;
    if let Some(mut entry) = sessions.remove(handle) {
        if let (Some(cache), Some(ip)) = (dns_cache.as_mut(), entry.pinned_synthetic_ip) {
            cache.unpin(ip);
        }
        // Drop the per-app attribution cache entry so a later flow to the same
        // destination (possibly a different app) re-resolves its owner.
        ripdpi_flow_app_attribution::evict_flow(entry.target_addr.ip());
        entry.cancel.cancel();
        entry.smoltcp_side.shutdown().await.ok();
        task = Some(entry.handle);
    }

    remove_tcp_socket(socket_set, handle);
    task
}
