use std::time::Duration;

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp::Socket as TcpSocket;
use tokio::io::AsyncWriteExt;

use crate::dns_cache::DnsCache;
use crate::ActiveSessions;

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
        entry.cancel.cancel();
        entry.smoltcp_side.shutdown().await.ok();
        match task_drain {
            TaskDrain::Abort => entry.handle.abort(),
            TaskDrain::Await(timeout) => {
                let _ = tokio::time::timeout(timeout, entry.handle).await;
            }
        }
    }

    if socket_set.iter().any(|(socket_handle, _)| socket_handle == handle) {
        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        if tcp.is_active() {
            tcp.close();
        }
        socket_set.remove(handle);
    }
}
