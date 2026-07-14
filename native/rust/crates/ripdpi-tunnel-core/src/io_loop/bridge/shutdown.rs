use smoltcp::iface::SocketSet;

use crate::ActiveSessions;
use crate::dns_cache::DnsCache;

use super::session_cleanup::take_session_task;
use crate::io_loop::task_shutdown::{TASK_SHUTDOWN_GRACE, drain_tasks};

pub(in crate::io_loop) async fn shutdown_active_sessions(
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) {
    for (_, entry) in sessions.iter_mut() {
        entry.cancel.cancel();
    }

    let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
    let mut tasks = Vec::with_capacity(handles.len());
    for handle in handles {
        if let Some(task) = take_session_task(handle, sessions, socket_set, dns_cache).await {
            tasks.push(task);
        }
    }
    drain_tasks(tasks, TASK_SHUTDOWN_GRACE).await;
}
