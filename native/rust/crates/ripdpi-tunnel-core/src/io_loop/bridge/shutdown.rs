use std::time::Duration;

use smoltcp::iface::SocketSet;
use tokio::io::AsyncWriteExt;

use crate::dns_cache::DnsCache;
use crate::ActiveSessions;

pub(in crate::io_loop) async fn shutdown_active_sessions(
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) {
    let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
    for handle in handles {
        if let Some(mut entry) = sessions.remove(handle) {
            // Release the DNS cache pin; the tunnel is shutting down so eviction
            // correctness no longer matters, but keep state consistent.
            if let (Some(cache), Some(ip)) = (dns_cache.as_mut(), entry.pinned_synthetic_ip) {
                cache.unpin(ip);
            }
            entry.cancel.cancel();
            entry.smoltcp_side.shutdown().await.ok();
            let _ = tokio::time::timeout(Duration::from_secs(5), entry.handle).await;
        }
        socket_set.remove(handle);
    }
}
