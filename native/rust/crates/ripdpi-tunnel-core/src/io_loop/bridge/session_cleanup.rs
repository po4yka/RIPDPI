use std::time::Duration;

use smoltcp::iface::{SocketHandle, SocketSet};
use tokio::io::AsyncWriteExt;

use crate::ActiveSessions;
use crate::dns_cache::DnsCache;

mod socket_removal;

use socket_removal::remove_tcp_socket;

pub(super) enum TaskDrain {
    Abort,
    Await(Duration),
}

pub(super) async fn remove_session(
    handle: SocketHandle,
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
    task_drain: TaskDrain,
) {
    if let Some(mut entry) = sessions.remove(handle) {
        if let (Some(cache), Some(ip)) = (dns_cache.as_mut(), entry.pinned_synthetic_ip) {
            cache.unpin(ip);
        }
        // Drop the per-app attribution cache entry so a later flow to the same
        // destination (possibly a different app) re-resolves its owner.
        ripdpi_flow_app_attribution::evict_flow(entry.target_addr.ip());
        entry.cancel.cancel();
        entry.smoltcp_side.shutdown().await.ok();
        match task_drain {
            TaskDrain::Abort => entry.handle.abort(),
            TaskDrain::Await(timeout) => {
                let _ = tokio::time::timeout(timeout, entry.handle).await;
            }
        }
    }

    remove_tcp_socket(socket_set, handle);
}
