use std::io;

use smoltcp::iface::{SocketHandle, SocketSet};
use tokio::task::JoinHandle;

use crate::ActiveSessions;
use crate::dns_cache::DnsCache;
use crate::io_loop::task_shutdown::{TASK_SHUTDOWN_GRACE, spawn_task_drain};

mod socket_removal;

use socket_removal::remove_tcp_socket;

pub(in crate::io_loop) fn remove_session(
    handle: SocketHandle,
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) {
    if let Some(task) = take_session_task(handle, sessions, socket_set, dns_cache) {
        spawn_task_drain(task, TASK_SHUTDOWN_GRACE);
    }
}

pub(super) fn take_session_task(
    handle: SocketHandle,
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) -> Option<JoinHandle<io::Result<()>>> {
    let entry = sessions.remove(handle)?;
    if let (Some(cache), Some(ip)) = (dns_cache.as_mut(), entry.pinned_synthetic_ip) {
        cache.unpin(ip);
    }
    if let Some(token) = entry.attribution_token {
        ripdpi_flow_app_attribution::evict_flow(token);
    }
    entry.cancel.cancel();
    drop(entry.smoltcp_side);
    remove_tcp_socket(socket_set, handle);
    Some(entry.handle)
}
