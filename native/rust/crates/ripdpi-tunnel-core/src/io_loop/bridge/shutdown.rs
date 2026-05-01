use std::time::Duration;

use smoltcp::iface::SocketSet;

use crate::dns_cache::DnsCache;
use crate::ActiveSessions;

use super::session_cleanup::{remove_session, TaskDrain};

pub(in crate::io_loop) async fn shutdown_active_sessions(
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    dns_cache: &mut Option<DnsCache>,
) {
    let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
    for handle in handles {
        remove_session(handle, sessions, socket_set, dns_cache, TaskDrain::Await(Duration::from_secs(5))).await;
    }
}
